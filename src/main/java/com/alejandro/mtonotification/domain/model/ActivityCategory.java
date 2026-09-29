package com.alejandro.mtonotification.domain.model;

/**
 * Las seis categorias del registro. Coinciden con el tipo {@code activity_category} de PostgreSQL:
 * anadir una es una migracion.
 *
 * <p>{@link #ACCESS} es distinta de las demas: lleva usuario e IP, tiene su endpoint y su permiso
 * aparte y nunca sale por el registro general.</p>
 */
public enum ActivityCategory {

    /** Accesos: logins, fallos, logouts, bloqueos, cambios de credenciales. Fuente: Keycloak. */
    ACCESS,

    /** Administracion de usuarios, roles y perfiles: mto-users y los eventos de administracion de Keycloak. */
    USERS,

    /** Datos maestros y trabajos de mto-configuration. */
    CONFIGURATION,

    /** Ordenes, defectos, inspecciones, turnos y material de mto-maintenance. */
    MAINTENANCE,

    /** Existencias, reservas y ajustes de mto-stock. */
    STOCK,

    /** Lo que este servicio dice de si mismo: avisos manuales, fuentes paradas, entregas muertas. */
    SYSTEM;

    /** El prefijo del tipo de evento que cae en esta categoria: {@code access.login} es ACCESS. */
    public String typePrefix() {
        return name().toLowerCase() + ".";
    }

    public static ActivityCategory ofType(String type) {
        DomainValidations.requireNonBlank(type, "type");
        for (ActivityCategory category : values()) {
            if (type.startsWith(category.typePrefix())) {
                return category;
            }
        }
        throw new IllegalArgumentException("Activity type '" + type + "' does not start with a known category");
    }
}
