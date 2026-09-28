package com.alejandro.mtonotification.infrastructure.web;

/**
 * Rutas de la API. El gateway publica {@code /api/notifications/**} y lo reescribe a esta raiz.
 *
 * <p>Los cuatro recursos son tambien las cuatro reglas de la cadena de seguridad: cada uno lleva
 * su permiso, y lo que no cuelga de ninguno se deniega.</p>
 */
public final class NotificationApiPaths {

    private NotificationApiPaths() {
    }

    public static final String BASE = "/api/v1/notifications";

    /** Mi bandeja: notificaciones dirigidas a mi, por usuario, perfil o rol de cliente. */
    public static final String INBOX = "/inbox";

    /** El registro de actividad: todo lo que pasa en el dominio salvo los accesos. */
    public static final String ACTIVITY = "/activity";

    /** Los accesos: logins, fallos, logouts y bloqueos, con usuario e IP. */
    public static final String ACCESS = "/access";

    /** Administracion: reglas, entregas, fuentes y avisos manuales. */
    public static final String ADMIN = "/admin";
}
