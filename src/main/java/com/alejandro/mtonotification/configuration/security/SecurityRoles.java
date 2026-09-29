package com.alejandro.mtonotification.configuration.security;

/**
 * Roles de cliente de Keycloak que comprueba esta API, ya normalizados a autoridad de Spring.
 *
 * <p>Son los nombres que declara {@code keycloak/mto-notification-partial-import.json} en
 * mayusculas y con guion bajo: {@code notification-inbox} llega como {@code ROLE_NOTIFICATION_INBOX}.
 * Un rol que se anade aqui sin anadirlo alli no lo tiene nadie y todo responde 403.</p>
 *
 * <p>Los permisos no se implican entre si: quien administra las entregas no lee por ello una
 * bandeja, y quien lee el registro de actividad no ve por ello los accesos, que llevan usuario e IP
 * y tienen su permiso aparte.</p>
 */
public final class SecurityRoles {

    private SecurityRoles() {
    }

    /** Mi bandeja: leer mis notificaciones, el contador de no leidas y marcarlas como leidas. */
    public static final String NOTIFICATION_INBOX = "NOTIFICATION_INBOX";

    /** El registro de actividad del dominio, todo menos los accesos. */
    public static final String NOTIFICATION_ACTIVITY_READ = "NOTIFICATION_ACTIVITY_READ";

    /** Los accesos: logins, fallos, logouts y bloqueos, con el usuario y la IP. */
    public static final String NOTIFICATION_ACCESS_READ = "NOTIFICATION_ACCESS_READ";

    /** Administracion: reglas cargadas, entregas y sus reintentos, estado de las fuentes, avisos manuales. */
    public static final String NOTIFICATION_ADMIN = "NOTIFICATION_ADMIN";

    /** Lectura de los endpoints de Actuator. */
    public static final String OPS_METRICS = "OPS_METRICS";

    /** Operaciones de Actuator que modifican estado. */
    public static final String OPS_WRITE = "OPS_WRITE";
}
