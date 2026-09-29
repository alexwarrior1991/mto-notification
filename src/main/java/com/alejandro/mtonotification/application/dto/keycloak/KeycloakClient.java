package com.alejandro.mtonotification.application.dto.keycloak;

/** Un cliente del realm: su id interno es lo que piden las rutas de roles de cliente. */
public record KeycloakClient(String id, String clientId) {
}
