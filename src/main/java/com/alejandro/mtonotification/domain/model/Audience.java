package com.alejandro.mtonotification.domain.model;

import java.util.Optional;

/**
 * A quien va una notificacion: una clase y una clave, y su forma serializada
 * {@code <KIND>:<clave>}, que es la que se guarda y la que el token de una persona reproduce al
 * leer la bandeja ({@code USER:alice}, {@code USER_ID:<sub>}, {@code PROFILE:mto-admin},
 * {@code CLIENT_ROLE:mto-stock-api:stock-read}).
 */
public record Audience(AudienceKind kind, String key) {

    private static final String SEPARATOR = ":";

    public Audience {
        DomainValidations.requireNonNull(kind, "kind");
        key = DomainValidations.requireNonBlank(key, "key").trim();
        if (kind == AudienceKind.CLIENT_ROLE && !key.contains(SEPARATOR)) {
            throw new IllegalArgumentException("A CLIENT_ROLE audience key is <client>:<role>, got '" + key + "'");
        }
    }

    public static Audience user(String username) {
        return new Audience(AudienceKind.USER, username);
    }

    /** La persona por su id de Keycloak: lo que las fuentes que no saben el nombre de usuario si saben. */
    public static Audience userId(String id) {
        return new Audience(AudienceKind.USER_ID, id);
    }

    public static Audience profile(String realmRole) {
        return new Audience(AudienceKind.PROFILE, realmRole);
    }

    public static Audience clientRole(String clientId, String role) {
        return new Audience(AudienceKind.CLIENT_ROLE,
                DomainValidations.requireNonBlank(clientId, "clientId").trim() + SEPARATOR
                        + DomainValidations.requireNonBlank(role, "role").trim());
    }

    /** Lo que se guarda en {@code notification_audience.audience_key} y lo que se compara al leer. */
    public String toKey() {
        return kind.name() + SEPARATOR + key;
    }

    /**
     * Lee {@code <KIND>:<clave>}. Una clase desconocida devuelve vacio en vez de fallar: una regla la
     * rechaza al arrancar, y una fila antigua con una clase que ya no existe no puede tumbar una
     * lectura.
     */
    public static Optional<Audience> parse(String serialized) {
        if (serialized == null) {
            return Optional.empty();
        }
        int separator = serialized.indexOf(SEPARATOR);
        if (separator <= 0 || separator == serialized.length() - 1) {
            return Optional.empty();
        }
        Optional<AudienceKind> kind = AudienceKind.parse(serialized.substring(0, separator));
        String key = serialized.substring(separator + 1).trim();
        if (kind.isEmpty() || key.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Audience(kind.get(), key));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
