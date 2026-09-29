package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakClient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakRole;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakUser;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import com.alejandro.mtonotification.application.service.AudienceResolver;
import com.alejandro.mtonotification.application.service.KeycloakDirectoryClient;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakProperties;
import com.alejandro.mtonotification.domain.model.Audience;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pone personas a una audiencia con el directorio de Keycloak, recordando cada respuesta unos
 * minutos: el correo no puede preguntar por cada entrega. Un rol de cliente se resuelve por sus
 * miembros directos MAS los de cada perfil ({@code mto-*}) que lo agrupa, porque Keycloak no
 * expande compuestos al listar quien tiene un rol. Un fallo del directorio no se recuerda.
 */
@Service
class KeycloakDirectoryAudienceResolver implements AudienceResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakDirectoryAudienceResolver.class);

    private final KeycloakDirectoryClient directory;
    private final KeycloakProperties properties;
    private final Cache<String, List<Recipient>> cache;

    KeycloakDirectoryAudienceResolver(KeycloakDirectoryClient directory, KeycloakProperties properties) {
        this.directory = directory;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.directory().cacheTtl())
                .maximumSize(10_000)
                .build();
    }

    @Override
    public List<Recipient> resolve(Audience audience) {
        List<Recipient> cached = cache.getIfPresent(audience.toKey());
        if (cached != null) {
            return cached;
        }
        List<Recipient> resolved;
        try {
            resolved = switch (audience.kind()) {
                case USER -> user(audience.key());
                case USER_ID -> userById(audience.key());
                case PROFILE -> realmRoleMembers(audience.key());
                case CLIENT_ROLE -> clientRoleMembers(audience.key());
            };
        } catch (DirectoryUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException failure) {
            throw new DirectoryUnavailableException("The directory could not resolve " + audience.toKey() + ": " + failure.getMessage(), failure);
        }
        cache.put(audience.toKey(), resolved);
        LOGGER.debug("Audience {} resolved to {} recipient(s)", audience.toKey(), resolved.size());
        return resolved;
    }

    private List<Recipient> user(String username) {
        Optional<KeycloakUser> user = directory.findUserByUsername(username);
        return user.filter(KeycloakUser::isEnabled).map(found -> List.of(toRecipient(found))).orElseGet(List::of);
    }

    private List<Recipient> userById(String id) {
        Optional<KeycloakUser> user = directory.findUserById(id);
        return user.filter(KeycloakUser::isEnabled).map(found -> List.of(toRecipient(found))).orElseGet(List::of);
    }

    private List<Recipient> realmRoleMembers(String roleName) {
        Map<String, Recipient> byUsername = new LinkedHashMap<>();
        collectRealmRoleMembers(roleName, byUsername);
        return List.copyOf(byUsername.values());
    }

    private List<Recipient> clientRoleMembers(String key) {
        int separator = key.indexOf(':');
        String clientId = key.substring(0, separator);
        String roleName = key.substring(separator + 1);
        Optional<KeycloakClient> client = directory.findClient(clientId);
        if (client.isEmpty()) {
            LOGGER.warn("Audience CLIENT_ROLE names a client that does not exist in the realm: {}", clientId);
            return List.of();
        }
        Map<String, Recipient> byUsername = new LinkedHashMap<>();
        int pageSize = properties.directory().pageSize();
        for (int first = 0; ; first += pageSize) {
            List<KeycloakUser> page = directory.clientRoleMembers(client.get().id(), roleName, first, pageSize);
            page.stream().filter(KeycloakUser::isEnabled).forEach(user -> byUsername.putIfAbsent(user.username(), toRecipient(user)));
            if (page.size() < pageSize) {
                break;
            }
        }
        // Los perfiles que agrupan ese rol: sus miembros lo tienen aunque Keycloak no los liste.
        String prefix = properties.directory().profilePrefix();
        for (int first = 0; ; first += pageSize) {
            List<KeycloakRole> roles = directory.realmRoles(first, pageSize);
            for (KeycloakRole role : roles) {
                if (role.name() == null || !role.name().startsWith(prefix) || !Boolean.TRUE.equals(role.composite())) {
                    continue;
                }
                boolean grantsRole = directory.clientCompositesOfRealmRole(role.name(), client.get().id()).stream()
                        .anyMatch(composite -> roleName.equals(composite.name()));
                if (grantsRole) {
                    collectRealmRoleMembers(role.name(), byUsername);
                }
            }
            if (roles.size() < pageSize) {
                break;
            }
        }
        return List.copyOf(byUsername.values());
    }

    private void collectRealmRoleMembers(String roleName, Map<String, Recipient> byUsername) {
        int pageSize = properties.directory().pageSize();
        for (int first = 0; ; first += pageSize) {
            List<KeycloakUser> page = directory.realmRoleMembers(roleName, first, pageSize);
            page.stream().filter(KeycloakUser::isEnabled).forEach(user -> byUsername.putIfAbsent(user.username(), toRecipient(user)));
            if (page.size() < pageSize) {
                break;
            }
        }
    }

    private static Recipient toRecipient(KeycloakUser user) {
        return new Recipient(user.username(), user.email() == null || user.email().isBlank() ? null : user.email().trim());
    }

    /** Para los tests y para vaciar tras un cambio en el realm. */
    void invalidateAll() {
        cache.invalidateAll();
    }
}
