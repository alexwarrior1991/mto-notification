package com.alejandro.mtonotification.infrastructure.keycloak;

/** Las rutas de la Admin API que este servicio usa, relativas a {@code /admin/realms/{realm}}. */
public final class KeycloakAdminApi {

    public static final String EVENTS = "/events";
    public static final String ADMIN_EVENTS = "/admin-events";
    public static final String USERS = "/users";
    public static final String CLIENTS = "/clients";
    public static final String ROLES = "/roles";

    private KeycloakAdminApi() {
    }
}
