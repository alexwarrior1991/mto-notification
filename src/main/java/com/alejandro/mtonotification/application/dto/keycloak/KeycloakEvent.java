package com.alejandro.mtonotification.application.dto.keycloak;

import java.util.Map;

/**
 * Subconjunto de la {@code EventRepresentation} de la Admin API: lo que este servicio lee de un
 * evento de acceso. Keycloak 26.1 no rellena ningun {@code id}, asi que la idempotencia es una
 * huella de estos campos.
 *
 * @param time      epoch en milisegundos
 * @param type      {@code LOGIN}, {@code LOGIN_ERROR}...
 * @param details   claves como {@code username}, {@code auth_method}, {@code reason}; se guardan por lista blanca
 * @param raw       el JSON del evento tal como llego; lo pone el cliente, no Keycloak
 */
public record KeycloakEvent(
        Long time,
        String type,
        String realmId,
        String clientId,
        String userId,
        String sessionId,
        String ipAddress,
        String error,
        Map<String, String> details,
        String raw
) {

    public KeycloakEvent withRaw(String json) {
        return new KeycloakEvent(time, type, realmId, clientId, userId, sessionId, ipAddress, error, details, json);
    }

    public String detail(String key) {
        return details == null ? null : details.get(key);
    }
}
