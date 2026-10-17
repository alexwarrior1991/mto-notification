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
import java.util.Map;

/**
 * Lo que mto-resources cuenta del personal, los equipos y la flota ({@code mto.resources.<entidad>.<evento>},
 * {@code docs/06-messaging.md} de ese repositorio): el alta, el cambio y la baja de una persona, de un
 * equipo (con su jefe, su supervisor y sus linemen) y de un vehiculo (con su modelo y si esta
 * disponible), con quien lo hizo y la correlacion de la peticion. El sujeto es la persona, el equipo o el
 * vehiculo por su id, con su codigo y su nombre de etiqueta.
 *
 * <p>Del {@code values} se guarda todo, como con mto-maintenance y mto-stock: el productor ya lo publica
 * por lista blanca y sin secretos (nunca el telefono), y el saneador del registro vuelve a pasarlo. Lo
 * que trae {@code republished: true} no se registra: es lo que {@code republish} vuelve a contar para
 * poner al dia a un consumidor, nada ha cambiado, y registrarlo avisaria a cada jefe de su equipo.</p>
 *
 * <p>La gravedad es la del hecho: una baja es {@code WARNING}, y tambien un vehiculo que ese cambio deja
 * sin disponibilidad ({@code availabilityChanged} y {@code available == false}); lo demas, {@code INFO}.</p>
 */
@Service
@RequiredArgsConstructor
class ResourcesSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "resources";
    static final String DEFAULT_ORIGIN = "mto-resources";

    static final String PERSON = "person";

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourcesSourceAdapter.class);

    private final ActivityIngestor activityIngestor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        DomainEventReader event = DomainEventReader.read(envelope, "Resources");
        PayloadReader values = event.values();
        if (Boolean.TRUE.equals(values.booleanValue("republished"))) {
            LOGGER.debug("Republished resources event not recorded: {}.{} {}", event.entityName(), event.eventName(), event.entityId());
            return;
        }
        String type = event.type(ActivityCategory.RESOURCES);
        if (!ActivityTypes.isKnown(type)) {
            LOGGER.warn("Resources event without a rule in the catalogue, recorded anyway: {}", type);
        }

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
                .severity(severity(type, values))
                .occurredAt(event.occurredAt())
                .actor(event.actor())
                .subject(Subject.of(event.entityName(), event.entityId(), label(event.entityName(), values)))
                .correlationId(event.correlationId())
                .payload(payload)
                .build());
    }

    /** El codigo y el nombre: el nombre completo de una persona, el nombre de un equipo o de un vehiculo. */
    static String label(String entityName, PayloadReader values) {
        String code = values.string("code");
        String name = PERSON.equals(entityName) ? values.string("fullName") : values.string("name");
        if (code != null && name != null) {
            return code + " " + name;
        }
        return code;
    }

    static ActivitySeverity severity(String type, PayloadReader values) {
        return switch (type) {
            case ActivityTypes.RESOURCES_PERSON_DEACTIVATED, ActivityTypes.RESOURCES_TEAM_DEACTIVATED,
                 ActivityTypes.RESOURCES_VEHICLE_DEACTIVATED -> ActivitySeverity.WARNING;
            case ActivityTypes.RESOURCES_VEHICLE_UPDATED -> Boolean.TRUE.equals(values.booleanValue("availabilityChanged"))
                    && Boolean.FALSE.equals(values.booleanValue("available")) ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
            default -> ActivitySeverity.INFO;
        };
    }
}
