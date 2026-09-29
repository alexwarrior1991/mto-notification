package com.alejandro.mtonotification.configuration.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.alejandro.mtonotification.domain.model.Audience;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Único punto de lectura del usuario autenticado. El resto de la aplicación no toca
 * {@code SecurityContextHolder} directamente.
 */
@Service
public class CurrentUserService {

    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    public Optional<Authentication> getAuthentication() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .filter(authentication -> !ANONYMOUS_PRINCIPAL.equals(authentication.getPrincipal()));
    }

    public Optional<Jwt> getJwt() {
        return getAuthentication()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(JwtAuthenticationToken::getToken);
    }

    /**
     * Devuelve vacío cuando no hay usuario autenticado, en lugar de sustituirlo por un nombre
     * inventado. Quien llama es quien sabe si la ausencia es esperada —un proceso de fondo— o un
     * síntoma, y solo él puede decidir qué registrar; devolver «system» desde aquí borraría esa
     * diferencia antes de que nadie pudiera verla.
     */
    public Optional<String> getUsername() {
        return getJwt()
                .map(jwt -> jwt.getClaimAsString(JwtClaimNames.PREFERRED_USERNAME))
                .filter(value -> !value.isBlank());
    }

    public Optional<String> getUserId() {
        return getJwt().map(Jwt::getSubject);
    }

    public Optional<String> getEmail() {
        return getJwt().map(jwt -> jwt.getClaimAsString(JwtClaimNames.EMAIL));
    }

    /**
     * Las claves de audiencia de la persona, tal como se guardan en {@code notification_audience}:
     * {@code USER:<usuario>}, {@code PROFILE:<rol de realm>} por cada rol de {@code realm_access}
     * (los perfiles son roles compuestos de realm, y el token trae su nombre) y
     * {@code CLIENT_ROLE:<cliente>:<rol>} por cada rol de {@code resource_access}. Es lo que hace
     * que la bandeja se resuelva al leer, sin expandir miembros al crear.
     */
    public List<String> getAudienceKeys() {
        Optional<Jwt> jwt = getJwt();
        if (jwt.isEmpty()) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        getUsername().ifPresent(username -> keys.add(Audience.user(username).toKey()));

        Map<String, Object> realmAccess = jwt.get().getClaimAsMap(JwtClaimNames.REALM_ACCESS);
        for (String role : roles(realmAccess)) {
            keys.add(Audience.profile(role).toKey());
        }

        Map<String, Object> resourceAccess = jwt.get().getClaimAsMap(JwtClaimNames.RESOURCE_ACCESS);
        if (resourceAccess != null) {
            for (Map.Entry<String, Object> client : resourceAccess.entrySet()) {
                if (client.getValue() instanceof Map<?, ?> access) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> clientAccess = (Map<String, Object>) access;
                    for (String role : roles(clientAccess)) {
                        keys.add(Audience.clientRole(client.getKey(), role).toKey());
                    }
                }
            }
        }
        return List.copyOf(keys);
    }

    private static List<String> roles(Map<String, Object> access) {
        if (access == null || !(access.get(JwtClaimNames.ROLES) instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .toList();
    }

    public List<String> getAuthorities() {
        return getAuthentication()
                .map(Authentication::getAuthorities)
                .stream()
                .flatMap(Collection::stream)
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    public boolean hasAuthority(String authority) {
        return getAuthorities().contains(authority);
    }

    public boolean hasRole(String role) {
        String normalizedRole = role.startsWith(SecurityAuthorityPrefixes.ROLE_PREFIX)
                ? role
                : SecurityAuthorityPrefixes.ROLE_PREFIX + role;

        return hasAuthority(normalizedRole);
    }
}
