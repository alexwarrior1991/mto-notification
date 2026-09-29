package com.alejandro.mtonotification.application.dto.keycloak;

/** Lo que el directorio dice de una persona: lo justo para dirigirle un correo. */
public record KeycloakUser(String id, String username, String email, Boolean enabled, String firstName, String lastName) {

    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
