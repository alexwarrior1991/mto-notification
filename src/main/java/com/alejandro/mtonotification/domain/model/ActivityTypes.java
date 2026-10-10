package com.alejandro.mtonotification.domain.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * El vocabulario de tipos de evento del dominio: {@code <categoria>.<sujeto>.<evento>}.
 *
 * <p>Es la lista contra la que se validan las reglas al arrancar (una regla que nombra un tipo que
 * no existe es una errata que no avisaria nunca) y la que dice a que categoria pertenece cada
 * tipo. Un adaptador solo emite tipos de aqui. Los tipos de las fuentes que llegan en fases
 * posteriores estan ya, porque el vocabulario es del dominio, no de quien lo produce.</p>
 */
public final class ActivityTypes {

    /** Misma forma que el {@code CHECK} de {@code activity_event.type}. */
    public static final Pattern SHAPE = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)+$");

    // --- ACCESS (Keycloak, eventos de acceso) ---
    public static final String ACCESS_LOGIN = "access.login";
    public static final String ACCESS_LOGIN_FAILED = "access.login.failed";
    public static final String ACCESS_LOGIN_STREAK = "access.login.streak";
    public static final String ACCESS_LOGOUT = "access.logout";
    public static final String ACCESS_LOGOUT_FAILED = "access.logout.failed";
    public static final String ACCESS_PASSWORD_CHANGED = "access.password.changed";
    public static final String ACCESS_PASSWORD_RESET = "access.password.reset";
    public static final String ACCESS_PASSWORD_RESET_REQUESTED = "access.password.reset-requested";
    public static final String ACCESS_TOTP_UPDATED = "access.totp.updated";
    public static final String ACCESS_TOTP_REMOVED = "access.totp.removed";
    public static final String ACCESS_CREDENTIAL_UPDATED = "access.credential.updated";
    public static final String ACCESS_CREDENTIAL_REMOVED = "access.credential.removed";
    public static final String ACCESS_LOCKOUT = "access.lockout";
    public static final String ACCESS_IMPERSONATION = "access.impersonation";
    public static final String ACCESS_PROFILE_UPDATED = "access.profile.updated";
    public static final String ACCESS_EMAIL_UPDATED = "access.email.updated";
    public static final String ACCESS_ACTIONS_EXECUTED = "access.actions.executed";
    public static final String ACCESS_OTHER = "access.other";

    // --- USERS (mto-users, fase 2c/2d) ---
    public static final String USERS_USER_CREATED = "users.user.created";
    public static final String USERS_USER_UPDATED = "users.user.updated";
    public static final String USERS_USER_ENABLED = "users.user.enabled";
    public static final String USERS_USER_DISABLED = "users.user.disabled";
    public static final String USERS_USER_DELETED = "users.user.deleted";
    public static final String USERS_USER_PASSWORD_RESET = "users.user.password-reset";
    public static final String USERS_USER_ACTIONS_EMAIL_SENT = "users.user.actions-email-sent";
    public static final String USERS_SESSION_REVOKED = "users.session.revoked";
    public static final String USERS_SESSION_ALL_REVOKED = "users.session.all-revoked";
    public static final String USERS_OFFLINE_SESSION_REVOKED = "users.offline-session.revoked";
    public static final String USERS_OFFLINE_SESSION_ALL_REVOKED = "users.offline-session.all-revoked";
    public static final String USERS_CREDENTIAL_DELETED = "users.credential.deleted";
    public static final String USERS_CLIENT_ROLES_ADDED = "users.client-roles.added";
    public static final String USERS_CLIENT_ROLES_REMOVED = "users.client-roles.removed";
    public static final String USERS_PROFILE_ASSIGNED = "users.profile.assigned";
    public static final String USERS_PROFILE_REMOVED = "users.profile.removed";

    // --- USERS (Keycloak, eventos de administracion) ---
    public static final String USERS_ADMIN_USER_CREATED = "users.admin.user-created";
    public static final String USERS_ADMIN_USER_UPDATED = "users.admin.user-updated";
    public static final String USERS_ADMIN_USER_DELETED = "users.admin.user-deleted";
    public static final String USERS_ADMIN_PASSWORD_RESET = "users.admin.password-reset";
    public static final String USERS_ADMIN_LOGOUT = "users.admin.logout";
    public static final String USERS_ADMIN_SESSION_DELETED = "users.admin.session-deleted";
    public static final String USERS_ADMIN_CREDENTIAL_DELETED = "users.admin.credential-deleted";
    public static final String USERS_ADMIN_CLIENT_ROLES_ADDED = "users.admin.client-roles-added";
    public static final String USERS_ADMIN_CLIENT_ROLES_REMOVED = "users.admin.client-roles-removed";
    public static final String USERS_ADMIN_REALM_ROLES_ADDED = "users.admin.realm-roles-added";
    public static final String USERS_ADMIN_REALM_ROLES_REMOVED = "users.admin.realm-roles-removed";
    public static final String USERS_ADMIN_CONSENT_REVOKED = "users.admin.consent-revoked";
    public static final String USERS_ADMIN_ACTIONS_EMAIL_SENT = "users.admin.actions-email-sent";
    public static final String USERS_ADMIN_OTHER = "users.admin.other";

    // --- CONFIGURATION (mto-configuration) ---
    public static final String CONFIGURATION_JOB_FINISHED = "configuration.job.finished";

    // --- MAINTENANCE (mto-maintenance, fase 3) ---
    public static final String MAINTENANCE_ORDER_CREATED = "maintenance.order.created";
    public static final String MAINTENANCE_ORDER_STATUS_CHANGED = "maintenance.order.status-changed";
    public static final String MAINTENANCE_ORDER_REASSIGNED = "maintenance.order.reassigned";
    public static final String MAINTENANCE_DEFECT_CREATED = "maintenance.defect.created";
    public static final String MAINTENANCE_DEFECT_STATUS_CHANGED = "maintenance.defect.status-changed";
    public static final String MAINTENANCE_INSPECTION_CREATED = "maintenance.inspection.created";
    public static final String MAINTENANCE_INSPECTION_ITEM_FAILED = "maintenance.inspection.item-failed";
    public static final String MAINTENANCE_INSPECTION_DEFECT_CREATED = "maintenance.inspection.defect-created";
    public static final String MAINTENANCE_INSPECTION_CORRECTIVE_ORDER_CREATED = "maintenance.inspection.corrective-order-created";
    public static final String MAINTENANCE_SHIFT_STARTED = "maintenance.shift.started";
    public static final String MAINTENANCE_SHIFT_CLOSED = "maintenance.shift.closed";
    public static final String MAINTENANCE_MATERIAL_REJECTED = "maintenance.material.rejected";
    public static final String MAINTENANCE_MATERIAL_FAILED = "maintenance.material.failed";
    public static final String MAINTENANCE_MATERIAL_IN_DOUBT = "maintenance.material.in-doubt";
    public static final String MAINTENANCE_ASSET_DISABLED = "maintenance.asset.disabled";
    public static final String MAINTENANCE_PREVENTIVE_DUE_SOON = "maintenance.preventive.due-soon";

    // --- STOCK (mto-stock, fase 4) ---
    public static final String STOCK_MATERIAL_BELOW_MINIMUM = "stock.material.below-minimum";
    public static final String STOCK_RESERVATION_CANCELLED = "stock.reservation.cancelled";
    public static final String STOCK_RESERVATION_RELEASED = "stock.reservation.released";
    public static final String STOCK_ADJUSTMENT_REGISTERED = "stock.adjustment.registered";

    // --- FIELD (mto-field, fase 5) ---
    public static final String FIELD_POSSESSION_OPENED = "field.possession.opened";
    public static final String FIELD_POSSESSION_CLOSED = "field.possession.closed";
    public static final String FIELD_POSSESSION_EVACUATION_ISSUED = "field.possession.evacuation-issued";
    public static final String FIELD_POSSESSION_EVACUATION_ACKNOWLEDGED = "field.possession.evacuation-acknowledged";
    public static final String FIELD_POSSESSION_EVACUATION_UNACKNOWLEDGED = "field.possession.evacuation-unacknowledged";
    public static final String FIELD_POSSESSION_CLEAR_OF_TRACK = "field.possession.clear-of-track";

    // --- SYSTEM (este servicio) ---
    public static final String SYSTEM_BROADCAST = "system.broadcast";
    public static final String SYSTEM_TEST_EMAIL = "system.test-email";
    public static final String SYSTEM_SOURCE_STALLED = "system.source.stalled";
    public static final String SYSTEM_DELIVERY_DEAD = "system.delivery.dead";

    /** Las ocho entidades de datos maestros que publica mto-configuration; sus tipos son {@code configuration.<entidad>.<operacion>}. */
    public static final Set<String> MASTER_DATA_ENTITIES = Set.of(
            "execution-package", "station", "track", "profile",
            "cantilever", "steady-arm", "disconnector", "section-insulator");

    public static final Set<String> MASTER_DATA_OPERATIONS = Set.of("created", "updated", "deleted");

    private static final Map<String, ActivityCategory> KNOWN = buildCatalogue();

    private ActivityTypes() {
    }

    public static boolean isWellFormed(String type) {
        return type != null && SHAPE.matcher(type).matches();
    }

    /** Bien formado y con una categoria delante. No exige estar en el catalogo: una fuente nueva puede emitir tipos nuevos. */
    public static String requireWellFormed(String type) {
        if (!isWellFormed(type)) {
            throw new IllegalArgumentException("Activity type '" + type + "' is not <category>.<subject>.<event> in lower case");
        }
        ActivityCategory.ofType(type);
        return type;
    }

    public static boolean isKnown(String type) {
        return type != null && KNOWN.containsKey(type);
    }

    public static Optional<ActivityCategory> categoryOf(String type) {
        if (!isWellFormed(type)) {
            return Optional.empty();
        }
        try {
            return Optional.of(ActivityCategory.ofType(type));
        } catch (IllegalArgumentException unknownCategory) {
            return Optional.empty();
        }
    }

    /** Todo el catalogo, ordenado, para documentacion y para la administracion. */
    public static Map<String, ActivityCategory> catalogue() {
        return KNOWN;
    }

    public static String masterDataType(String entityName, String operation) {
        String entity = DomainValidations.requireNonBlank(entityName, "entityName").trim().toLowerCase();
        String op = DomainValidations.requireNonBlank(operation, "operation").trim().toLowerCase();
        return requireWellFormed(ActivityCategory.CONFIGURATION.typePrefix() + entity + "." + op);
    }

    private static Map<String, ActivityCategory> buildCatalogue() {
        Map<String, ActivityCategory> types = new TreeMap<>();
        for (String type : new String[]{
                ACCESS_LOGIN, ACCESS_LOGIN_FAILED, ACCESS_LOGIN_STREAK, ACCESS_LOGOUT, ACCESS_LOGOUT_FAILED,
                ACCESS_PASSWORD_CHANGED, ACCESS_PASSWORD_RESET, ACCESS_PASSWORD_RESET_REQUESTED,
                ACCESS_TOTP_UPDATED, ACCESS_TOTP_REMOVED, ACCESS_CREDENTIAL_UPDATED, ACCESS_CREDENTIAL_REMOVED,
                ACCESS_LOCKOUT, ACCESS_IMPERSONATION, ACCESS_PROFILE_UPDATED, ACCESS_EMAIL_UPDATED,
                ACCESS_ACTIONS_EXECUTED, ACCESS_OTHER,
                USERS_USER_CREATED, USERS_USER_UPDATED, USERS_USER_ENABLED, USERS_USER_DISABLED, USERS_USER_DELETED,
                USERS_USER_PASSWORD_RESET, USERS_USER_ACTIONS_EMAIL_SENT, USERS_SESSION_REVOKED, USERS_SESSION_ALL_REVOKED,
                USERS_OFFLINE_SESSION_REVOKED, USERS_OFFLINE_SESSION_ALL_REVOKED, USERS_CREDENTIAL_DELETED,
                USERS_CLIENT_ROLES_ADDED, USERS_CLIENT_ROLES_REMOVED, USERS_PROFILE_ASSIGNED, USERS_PROFILE_REMOVED,
                USERS_ADMIN_USER_CREATED, USERS_ADMIN_USER_UPDATED, USERS_ADMIN_USER_DELETED, USERS_ADMIN_PASSWORD_RESET,
                USERS_ADMIN_LOGOUT, USERS_ADMIN_SESSION_DELETED, USERS_ADMIN_CREDENTIAL_DELETED,
                USERS_ADMIN_CLIENT_ROLES_ADDED, USERS_ADMIN_CLIENT_ROLES_REMOVED, USERS_ADMIN_REALM_ROLES_ADDED,
                USERS_ADMIN_REALM_ROLES_REMOVED, USERS_ADMIN_CONSENT_REVOKED, USERS_ADMIN_ACTIONS_EMAIL_SENT, USERS_ADMIN_OTHER,
                CONFIGURATION_JOB_FINISHED,
                MAINTENANCE_ORDER_CREATED, MAINTENANCE_ORDER_STATUS_CHANGED, MAINTENANCE_ORDER_REASSIGNED,
                MAINTENANCE_DEFECT_CREATED, MAINTENANCE_DEFECT_STATUS_CHANGED, MAINTENANCE_INSPECTION_CREATED,
                MAINTENANCE_INSPECTION_ITEM_FAILED, MAINTENANCE_INSPECTION_DEFECT_CREATED,
                MAINTENANCE_INSPECTION_CORRECTIVE_ORDER_CREATED, MAINTENANCE_SHIFT_STARTED, MAINTENANCE_SHIFT_CLOSED,
                MAINTENANCE_MATERIAL_REJECTED, MAINTENANCE_MATERIAL_FAILED, MAINTENANCE_MATERIAL_IN_DOUBT,
                MAINTENANCE_ASSET_DISABLED, MAINTENANCE_PREVENTIVE_DUE_SOON,
                STOCK_MATERIAL_BELOW_MINIMUM, STOCK_RESERVATION_CANCELLED, STOCK_RESERVATION_RELEASED,
                STOCK_ADJUSTMENT_REGISTERED,
                FIELD_POSSESSION_OPENED, FIELD_POSSESSION_CLOSED, FIELD_POSSESSION_EVACUATION_ISSUED,
                FIELD_POSSESSION_EVACUATION_ACKNOWLEDGED, FIELD_POSSESSION_EVACUATION_UNACKNOWLEDGED,
                FIELD_POSSESSION_CLEAR_OF_TRACK,
                SYSTEM_BROADCAST, SYSTEM_TEST_EMAIL, SYSTEM_SOURCE_STALLED, SYSTEM_DELIVERY_DEAD}) {
            types.put(type, ActivityCategory.ofType(type));
        }
        for (String entity : MASTER_DATA_ENTITIES) {
            for (String operation : MASTER_DATA_OPERATIONS) {
                types.put(ActivityCategory.CONFIGURATION.typePrefix() + entity + "." + operation, ActivityCategory.CONFIGURATION);
            }
        }
        return Map.copyOf(types);
    }
}
