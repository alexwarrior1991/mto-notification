package com.alejandro.mtonotification.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Las clases de destinatario que la bandeja sabe resolver con lo que trae el token de la persona.
 *
 * <p>En la base de datos {@code kind} es un {@code varchar}, no un tipo enumerado, a proposito: un
 * {@code TEAM} o un {@code ZONE} para {@code mto-field} es una constante mas aqui y un dato que el
 * token tendra que traer, nunca un {@code ALTER TYPE}.</p>
 */
public enum AudienceKind {

    /** Una persona, por su {@code preferred_username}. */
    USER,

    /** Un perfil: un rol compuesto de realm ({@code mto-maintenance-manager}), tal como viene en {@code realm_access.roles}. */
    PROFILE,

    /** Un rol de cliente, {@code <cliente>:<rol>} ({@code mto-stock-api:stock-read}), de {@code resource_access}. */
    CLIENT_ROLE;

    public static Optional<AudienceKind> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (AudienceKind kind : values()) {
            if (kind.name().equals(normalized)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
