package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.ActivitySourceAdapter;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Subject;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lo que mto-maintenance cuenta de si mismo ({@code mto.maintenance.<entidad>.<evento>},
 * {@code docs/06-messaging.md} de ese repositorio): una linea por evento, con la persona que lo
 * hizo (o el sistema: el aviso diario de preventivos, el reintento automatico de stock) y la
 * correlacion de la peticion. El tipo sale del nombre del evento ({@code maintenance.order.created},
 * {@code maintenance.material.rejected}...) y el sujeto es la entidad con su codigo de etiqueta
 * ({@code order}/{@code MO-000123}), que es lo que el aviso enlaza y lo que el registro agrupa.
 *
 * <p>Del {@code values} se guarda todo: es el contrato del productor, que ya lo publica por lista
 * blanca y rechaza cualquier clave que huela a secreto, y el saneador del registro vuelve a
 * pasarlo. Asi una clave nueva alli no exige nada aqui, y las reglas leen lo que necesitan
 * ({@code payload.type}, {@code payload.to}, {@code payload.assignedUser},
 * {@code payload.stockErrorCode}, {@code payload.overdueCount}...).</p>
 *
 * <p>La gravedad de la linea es la del hecho: una orden urgente y un defecto o una inspeccion
 * criticos son {@code CRITICAL}; un defecto grave, una inspeccion con defecto mayor, un punto en
 * {@code DEFECT}, una orden cancelada, un material que el almacen rechaza o no contesta, un activo
 * desactivado y unos preventivos ya vencidos son {@code WARNING}; lo demas es {@code INFO}. La
 * regla puede subirla en el aviso.</p>
 */
@Service
@RequiredArgsConstructor
class MaintenanceSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "maintenance";
    static final String DEFAULT_ORIGIN = "mto-maintenance";

    static final String ORDER = "order";
    static final String DEFECT = "defect";
    static final String INSPECTION = "inspection";
    static final String MATERIAL = "material";
    static final String PREVENTIVE = "preventive";

    static final String URGENT = "URGENT";
    static final String CRITICAL = "CRITICAL";
    static final String HIGH = "HIGH";
    static final String UNSAFE = "UNSAFE";
    static final String MAJOR_DEFECT = "MAJOR_DEFECT";
    static final String CANCELLED = "CANCELLED";

    static final Set<String> WARNING_TYPES = Set.of(
            ActivityTypes.MAINTENANCE_INSPECTION_ITEM_FAILED, ActivityTypes.MAINTENANCE_MATERIAL_REJECTED,
            ActivityTypes.MAINTENANCE_MATERIAL_FAILED, ActivityTypes.MAINTENANCE_MATERIAL_IN_DOUBT,
            ActivityTypes.MAINTENANCE_ASSET_DISABLED);

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceSourceAdapter.class);

    private final ActivityIngestor activityIngestor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        DomainEventReader event = DomainEventReader.read(envelope, "Maintenance");
        String type = event.type(ActivityCategory.MAINTENANCE);
        if (!ActivityTypes.isKnown(type)) {
            LOGGER.warn("Maintenance event without a rule in the catalogue, recorded anyway: {}", type);
        }
        PayloadReader values = event.values();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", event.entityName());
        payload.put("eventName", event.eventName());
        values.all().forEach((key, value) -> {
            if (value != null) {
                payload.put(key, value);
            }
        });

        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(event.origin(DEFAULT_ORIGIN), event.sourceEventId())
                .type(type)
                .severity(severity(type, event.entityName(), values))
                .occurredAt(event.occurredAt())
                .actor(event.actor())
                .subject(Subject.of(event.entityName(), event.entityId(), label(event.entityName(), values)))
                .correlationId(event.correlationId())
                .payload(payload)
                .build());
    }

    /** El codigo de lo que paso: el de la orden, el defecto, la inspeccion, el turno o el activo; el material, por su orden. */
    static String label(String entityName, PayloadReader values) {
        return switch (entityName) {
            case MATERIAL -> values.string("orderCode") == null ? values.string("materialCode")
                    : values.string("orderCode") + " " + values.string("materialCode");
            case PREVENTIVE -> values.longValue("count") == null ? null : values.longValue("count") + " due";
            default -> values.string("code");
        };
    }

    static ActivitySeverity severity(String type, String entityName, PayloadReader values) {
        if (WARNING_TYPES.contains(type)) {
            return ActivitySeverity.WARNING;
        }
        return switch (type) {
            case ActivityTypes.MAINTENANCE_ORDER_CREATED -> URGENT.equals(upper(values.string("type")))
                    ? ActivitySeverity.CRITICAL : ActivitySeverity.INFO;
            case ActivityTypes.MAINTENANCE_ORDER_STATUS_CHANGED -> CANCELLED.equals(upper(values.string("to")))
                    ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
            case ActivityTypes.MAINTENANCE_DEFECT_CREATED, ActivityTypes.MAINTENANCE_INSPECTION_DEFECT_CREATED ->
                    bySeverity(upper(values.string("severity")));
            case ActivityTypes.MAINTENANCE_INSPECTION_CREATED, ActivityTypes.MAINTENANCE_INSPECTION_CORRECTIVE_ORDER_CREATED ->
                    byResult(upper(values.string("result")));
            case ActivityTypes.MAINTENANCE_PREVENTIVE_DUE_SOON -> values.longValue("overdueCount") != null && values.longValue("overdueCount") > 0
                    ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
            default -> ActivitySeverity.INFO;
        };
    }

    private static ActivitySeverity bySeverity(String defectSeverity) {
        if (CRITICAL.equals(defectSeverity)) {
            return ActivitySeverity.CRITICAL;
        }
        return HIGH.equals(defectSeverity) ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
    }

    private static ActivitySeverity byResult(String result) {
        if (UNSAFE.equals(result)) {
            return ActivitySeverity.CRITICAL;
        }
        return MAJOR_DEFECT.equals(result) ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
