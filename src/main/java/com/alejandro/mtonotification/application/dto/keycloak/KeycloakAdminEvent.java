package com.alejandro.mtonotification.application.dto.keycloak;

/**
 * Subconjunto de la {@code AdminEventRepresentation}: quien cambio que en el realm.
 *
 * @param operationType  {@code CREATE}, {@code UPDATE}, {@code DELETE}, {@code ACTION}
 * @param resourceType   {@code USER}, {@code USER_SESSION}, {@code CLIENT_ROLE_MAPPING}...
 * @param resourcePath   {@code users/{id}}, {@code users/{id}/reset-password}...
 * @param representation el JSON del recurso, ya sin secretos (Keycloak lo pasa por
 *                       {@code StripSecretsUtils}); aqui solo se leen los nombres de rol
 * @param raw            el JSON del evento tal como llego; lo pone el cliente
 */
public record KeycloakAdminEvent(
        Long time,
        String realmId,
        AuthDetails authDetails,
        String operationType,
        String resourceType,
        String resourcePath,
        String representation,
        String error,
        String raw
) {

    /** Quien lo hizo: el cliente con el que se autentico, su usuario y su IP. */
    public record AuthDetails(String realmId, String clientId, String userId, String ipAddress) {
    }

    public KeycloakAdminEvent withRaw(String json) {
        return new KeycloakAdminEvent(time, realmId, authDetails, operationType, resourceType, resourcePath,
                representation, error, json);
    }

    public String clientId() {
        return authDetails == null ? null : authDetails.clientId();
    }

    public String actorUserId() {
        return authDetails == null ? null : authDetails.userId();
    }
}
