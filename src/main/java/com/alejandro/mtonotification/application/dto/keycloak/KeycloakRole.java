package com.alejandro.mtonotification.application.dto.keycloak;

/** Un rol del realm o de un cliente. */
public record KeycloakRole(String id, String name, Boolean composite, Boolean clientRole, String containerId) {
}
