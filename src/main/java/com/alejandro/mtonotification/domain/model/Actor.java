package com.alejandro.mtonotification.domain.model;

/**
 * El autor de un evento.
 *
 * @param kind     persona, servicio o sistema
 * @param username el nombre de usuario en el realm; para una cuenta de servicio,
 *                 {@code service-account-<clientId>}; {@code null} si no se sabe
 * @param id       el {@code sub} del token o el id de usuario de Keycloak, que no cambia si
 *                 alguien se renombra; {@code null} si no viaja
 */
public record Actor(ActorKind kind, String username, String id) {

    /** Prefijo con el que Keycloak nombra al usuario de una cuenta de servicio. */
    public static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    public Actor {
        DomainValidations.requireNonNull(kind, "kind");
        username = DomainValidations.trimToNull(username);
        id = DomainValidations.trimToNull(id);
    }

    public static Actor person(String username, String id) {
        return new Actor(ActorKind.PERSON, username, id);
    }

    /** La cuenta de servicio de un cliente: el nombre es el que Keycloak da a su usuario. */
    public static Actor service(String clientId, String id) {
        String client = DomainValidations.trimToNull(clientId);
        return new Actor(ActorKind.SERVICE, client == null ? null : SERVICE_ACCOUNT_PREFIX + client, id);
    }

    public static Actor system() {
        return new Actor(ActorKind.SYSTEM, null, null);
    }

    /**
     * Clasifica un nombre de usuario que llega de otro servicio: los de {@code service-account-}
     * son cuentas de servicio, el resto personas. Sin nombre, es el sistema.
     */
    public static Actor ofUsername(String username, String id) {
        String name = DomainValidations.trimToNull(username);
        if (name == null) {
            return id == null ? system() : new Actor(ActorKind.SYSTEM, null, id);
        }
        return name.startsWith(SERVICE_ACCOUNT_PREFIX)
                ? new Actor(ActorKind.SERVICE, name, id)
                : person(name, id);
    }
}
