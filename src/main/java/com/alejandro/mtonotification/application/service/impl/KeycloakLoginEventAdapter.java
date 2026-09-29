package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.Fingerprints;
import com.alejandro.mtonotification.domain.model.Subject;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Los eventos de acceso de Keycloak, a lineas ACCESS. Lo de maquina (los tokens de las cuentas
 * de servicio, los refrescos) se ignora aunque el realm lo registrase; lo que no se conoce entra
 * como {@code access.other} con su tipo en el payload. Del {@code details} solo pasan las claves
 * de la lista blanca: nunca un codigo, un token ni una URI de vuelta.
 */
@Service
public class KeycloakLoginEventAdapter {

    public static final String SOURCE_SERVICE = "keycloak-login";

    static final Set<String> IGNORED_TYPES = Set.of(
            "CLIENT_LOGIN", "CLIENT_LOGIN_ERROR", "CODE_TO_TOKEN", "CODE_TO_TOKEN_ERROR",
            "REFRESH_TOKEN", "REFRESH_TOKEN_ERROR", "INTROSPECT_TOKEN", "INTROSPECT_TOKEN_ERROR",
            "USER_INFO_REQUEST", "USER_INFO_REQUEST_ERROR", "PERMISSION_TOKEN", "PERMISSION_TOKEN_ERROR",
            "TOKEN_EXCHANGE", "TOKEN_EXCHANGE_ERROR", "CLIENT_INFO", "CLIENT_INFO_ERROR",
            "VALIDATE_ACCESS_TOKEN", "VALIDATE_ACCESS_TOKEN_ERROR", "PUSHED_AUTHORIZATION_REQUEST",
            "PUSHED_AUTHORIZATION_REQUEST_ERROR", "AUTHREQID_TO_TOKEN", "AUTHREQID_TO_TOKEN_ERROR");

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("LOGIN", ActivityTypes.ACCESS_LOGIN),
            Map.entry("LOGIN_ERROR", ActivityTypes.ACCESS_LOGIN_FAILED),
            Map.entry("LOGOUT", ActivityTypes.ACCESS_LOGOUT),
            Map.entry("LOGOUT_ERROR", ActivityTypes.ACCESS_LOGOUT_FAILED),
            Map.entry("UPDATE_PASSWORD", ActivityTypes.ACCESS_PASSWORD_CHANGED),
            Map.entry("RESET_PASSWORD", ActivityTypes.ACCESS_PASSWORD_RESET),
            Map.entry("SEND_RESET_PASSWORD", ActivityTypes.ACCESS_PASSWORD_RESET_REQUESTED),
            Map.entry("UPDATE_TOTP", ActivityTypes.ACCESS_TOTP_UPDATED),
            Map.entry("REMOVE_TOTP", ActivityTypes.ACCESS_TOTP_REMOVED),
            Map.entry("UPDATE_CREDENTIAL", ActivityTypes.ACCESS_CREDENTIAL_UPDATED),
            Map.entry("REMOVE_CREDENTIAL", ActivityTypes.ACCESS_CREDENTIAL_REMOVED),
            Map.entry("USER_DISABLED_BY_TEMPORARY_LOCKOUT", ActivityTypes.ACCESS_LOCKOUT),
            Map.entry("USER_DISABLED_BY_PERMANENT_LOCKOUT", ActivityTypes.ACCESS_LOCKOUT),
            Map.entry("IMPERSONATE", ActivityTypes.ACCESS_IMPERSONATION),
            Map.entry("UPDATE_PROFILE", ActivityTypes.ACCESS_PROFILE_UPDATED),
            Map.entry("UPDATE_EMAIL", ActivityTypes.ACCESS_EMAIL_UPDATED),
            Map.entry("EXECUTE_ACTIONS", ActivityTypes.ACCESS_ACTIONS_EXECUTED),
            Map.entry("EXECUTE_ACTION_TOKEN", ActivityTypes.ACCESS_ACTIONS_EXECUTED));

    /** Las claves de {@code details} que se guardan. Lo demas no entra, se llame como se llame. */
    static final List<String> DETAIL_WHITELIST = List.of(
            "username", "auth_method", "auth_type", "reason", "identity_provider", "credential_type",
            "impersonator", "impersonator_realm", "updated_email", "previous_email", "updated_first_name",
            "updated_last_name", "custom_required_action", "remember_me");

    public static String fingerprint(KeycloakEvent event) {
        return Fingerprints.sha256(Fingerprints.canonical(
                event.time(), event.type(), event.realmId(), event.clientId(), event.userId(), event.sessionId(),
                event.ipAddress(), event.error(), Fingerprints.canonical(event.details())));
    }

    public Optional<ActivityEventDraft> toDraft(KeycloakEvent event) {
        String keycloakType = event.type() == null ? "" : event.type().trim().toUpperCase();
        if (keycloakType.isEmpty() || IGNORED_TYPES.contains(keycloakType)) {
            return Optional.empty();
        }
        String type = TYPES.getOrDefault(keycloakType, ActivityTypes.ACCESS_OTHER);
        String username = event.detail("username");
        Actor actor = username == null && event.userId() == null ? Actor.system() : Actor.person(username, event.userId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keycloakType", keycloakType);
        if (event.clientId() != null) {
            payload.put("clientId", event.clientId());
        }
        if (event.error() != null) {
            payload.put("error", event.error());
        }
        if (keycloakType.endsWith("_LOCKOUT")) {
            payload.put("permanent", keycloakType.contains("PERMANENT"));
        }
        for (String key : DETAIL_WHITELIST) {
            String value = event.detail(key);
            if (value != null && !value.isBlank()) {
                payload.put(key, value);
            }
        }

        return Optional.of(ActivityEventDraft.builder()
                .source(SOURCE_SERVICE, fingerprint(event))
                .type(type)
                .severity(severity(keycloakType))
                .occurredAt(event.time() == null ? Instant.now() : Instant.ofEpochMilli(event.time()))
                .actor(actor)
                .subject(Subject.of("user", event.userId(), username))
                .ipAddress(event.ipAddress())
                .payload(payload)
                .build());
    }

    private static ActivitySeverity severity(String keycloakType) {
        if (keycloakType.endsWith("_LOCKOUT")) {
            return ActivitySeverity.CRITICAL;
        }
        if (keycloakType.endsWith("_ERROR") || keycloakType.startsWith("REMOVE_") || "IMPERSONATE".equals(keycloakType)) {
            return ActivitySeverity.WARNING;
        }
        return ActivitySeverity.INFO;
    }
}
