package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakClient;
import com.alejandro.mtonotification.application.service.KeycloakDirectoryClient;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakProperties;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.ActorKind;
import com.alejandro.mtonotification.domain.model.Fingerprints;
import com.alejandro.mtonotification.domain.model.Subject;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Los eventos de administracion de Keycloak, a lineas USERS ({@code users.admin.*}). El actor es
 * la cuenta de servicio de mto-users cuando el cambio vino de la aplicacion, y quien fuera (un id,
 * sin nombre: Keycloak no lo da) cuando vino de la consola o de kcadm. Solo se ensenan los
 * cambios sobre usuarios, sesiones y roles; el resto entra como {@code users.admin.other}. De la
 * representacion solo se leen los nombres de rol de un role-mapping. La IP se descarta: no es ACCESS.
 */
@Service
public class KeycloakAdminEventAdapter {

    public static final String SOURCE_SERVICE = "keycloak-admin";

    private static final Pattern USER_PATH = Pattern.compile("^users/([0-9a-fA-F-]{36})(?:/(.*))?$");
    private static final Pattern SESSION_PATH = Pattern.compile("^sessions/([0-9a-fA-F-]{36})$");

    private final KeycloakProperties properties;
    private final JsonMapper jsonMapper;
    private final KeycloakDirectoryClient directory;
    /** id interno → clientId. Un cliente no cambia de id en su vida, asi que se recuerda sin caducidad. */
    private final Map<String, String> clientIdsByInternalId = new ConcurrentHashMap<>();

    public KeycloakAdminEventAdapter(KeycloakProperties properties, JsonMapper jsonMapper, KeycloakDirectoryClient directory) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.directory = directory;
    }

    public static String fingerprint(KeycloakAdminEvent event) {
        KeycloakAdminEvent.AuthDetails auth = event.authDetails();
        return Fingerprints.sha256(Fingerprints.canonical(
                event.time(), event.realmId(), event.operationType(), event.resourceType(), event.resourcePath(),
                auth == null ? null : auth.clientId(), auth == null ? null : auth.userId(),
                auth == null ? null : auth.ipAddress(), event.error(),
                event.representation() == null ? "" : Fingerprints.sha256(event.representation())));
    }

    public Optional<ActivityEventDraft> toDraft(KeycloakAdminEvent event) {
        String operation = event.operationType() == null ? "" : event.operationType().trim().toUpperCase();
        String resourceType = event.resourceType() == null ? "" : event.resourceType().trim().toUpperCase();
        String path = event.resourcePath() == null ? "" : event.resourcePath().trim();
        if (operation.isEmpty()) {
            return Optional.empty();
        }

        String type = type(operation, resourceType, path);
        Subject subject = subject(path);
        String clientId = clientId(event);
        Actor actor = actor(clientId, event);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientId", clientId);
        if (event.authDetails() != null && event.authDetails().realmId() != null) {
            payload.put("authRealmId", event.authDetails().realmId());
        }
        payload.put("operationType", operation);
        payload.put("resourceType", resourceType);
        payload.put("resourcePath", path);
        if (event.error() != null) {
            payload.put("error", event.error());
        }
        if (type.endsWith("roles-added") || type.endsWith("roles-removed")) {
            payload.put("roles", roleNames(event.representation()));
        }

        return Optional.of(ActivityEventDraft.builder()
                .source(SOURCE_SERVICE, fingerprint(event))
                .type(type)
                .severity(severity(type, operation))
                .occurredAt(event.time() == null ? Instant.now() : Instant.ofEpochMilli(event.time()))
                .actor(actor)
                .subject(subject)
                .payload(payload)
                .build());
    }

    /**
     * {@code authDetails.clientId} es el id interno del cliente (un UUID), no su {@code clientId}: se
     * resuelve en el directorio del realm y se recuerda. Un cliente que no esta en el realm (la consola
     * de {@code master}, kcadm) se queda con su UUID, que a efectos de la regla es «fuera de la
     * aplicacion». Si el directorio no responde, el evento se queda FAILED en el inbox y la siguiente
     * pasada lo reintenta.
     */
    String clientId(KeycloakAdminEvent event) {
        String internalId = event.clientId();
        if (internalId == null || internalId.isBlank()) {
            return null;
        }
        return clientIdsByInternalId.computeIfAbsent(internalId.trim(),
                id -> directory.findClientById(id).map(KeycloakClient::clientId).filter(value -> !value.isBlank()).orElse(id));
    }

    private Actor actor(String clientId, KeycloakAdminEvent event) {
        if (clientId != null && clientId.equals(properties.events().usersServiceClientId())) {
            return Actor.service(clientId, event.actorUserId());
        }
        return event.actorUserId() == null ? Actor.system() : new Actor(ActorKind.PERSON, null, event.actorUserId());
    }

    static String type(String operation, String resourceType, String path) {
        Matcher user = USER_PATH.matcher(path);
        if (user.matches()) {
            String tail = user.group(2) == null ? "" : user.group(2);
            if (tail.isEmpty()) {
                return switch (operation) {
                    case "CREATE" -> ActivityTypes.USERS_ADMIN_USER_CREATED;
                    case "UPDATE" -> ActivityTypes.USERS_ADMIN_USER_UPDATED;
                    case "DELETE" -> ActivityTypes.USERS_ADMIN_USER_DELETED;
                    default -> ActivityTypes.USERS_ADMIN_OTHER;
                };
            }
            if (tail.equals("reset-password")) {
                return ActivityTypes.USERS_ADMIN_PASSWORD_RESET;
            }
            if (tail.equals("logout")) {
                return ActivityTypes.USERS_ADMIN_LOGOUT;
            }
            if (tail.equals("execute-actions-email")) {
                return ActivityTypes.USERS_ADMIN_ACTIONS_EMAIL_SENT;
            }
            if (tail.startsWith("credentials/") && "DELETE".equals(operation)) {
                return ActivityTypes.USERS_ADMIN_CREDENTIAL_DELETED;
            }
            if (tail.startsWith("consents/") && "DELETE".equals(operation)) {
                return ActivityTypes.USERS_ADMIN_CONSENT_REVOKED;
            }
            if (tail.startsWith("role-mappings/clients/")) {
                return "DELETE".equals(operation) ? ActivityTypes.USERS_ADMIN_CLIENT_ROLES_REMOVED : ActivityTypes.USERS_ADMIN_CLIENT_ROLES_ADDED;
            }
            if (tail.startsWith("role-mappings/realm")) {
                return "DELETE".equals(operation) ? ActivityTypes.USERS_ADMIN_REALM_ROLES_REMOVED : ActivityTypes.USERS_ADMIN_REALM_ROLES_ADDED;
            }
            return ActivityTypes.USERS_ADMIN_OTHER;
        }
        if ("USER".equals(resourceType) && "CREATE".equals(operation)) {
            return ActivityTypes.USERS_ADMIN_USER_CREATED;
        }
        if ("USER_SESSION".equals(resourceType) && "DELETE".equals(operation)) {
            return ActivityTypes.USERS_ADMIN_SESSION_DELETED;
        }
        return ActivityTypes.USERS_ADMIN_OTHER;
    }

    static Subject subject(String path) {
        Matcher user = USER_PATH.matcher(path);
        if (user.matches()) {
            return Subject.of("user", user.group(1));
        }
        Matcher session = SESSION_PATH.matcher(path);
        if (session.matches()) {
            return Subject.of("session", session.group(1));
        }
        return path.isEmpty() ? Subject.none() : Subject.of("realm-resource", path.length() > 200 ? path.substring(0, 200) : path);
    }

    private static ActivitySeverity severity(String type, String operation) {
        if ("DELETE".equals(operation) || type.endsWith("password-reset") || type.endsWith("credential-deleted")
                || type.endsWith("session-deleted") || type.endsWith("logout")) {
            return ActivitySeverity.WARNING;
        }
        return ActivitySeverity.INFO;
    }

    private List<String> roleNames(String representation) {
        if (representation == null || representation.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = jsonMapper.readTree(representation);
            List<String> names = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode role : node) {
                    JsonNode name = role.get("name");
                    if (name != null && name.isString()) {
                        names.add(name.asString());
                    }
                }
            }
            return List.copyOf(names);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }
}
