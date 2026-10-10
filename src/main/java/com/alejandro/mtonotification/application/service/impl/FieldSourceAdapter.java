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
 * Lo que mto-field cuenta de una posesion de via ({@code mto.field.possession.<evento>},
 * {@code docs/06-messaging.md} de ese repositorio, <i>Published events</i>): la posesion abierta y
 * cerrada, el desalojo emitido, cada acuse de un equipo, el equipo que no acusa dentro del plazo
 * (lo publica su vigilante, sin persona detras) y la salida de via de cada equipo. El actor es el
 * responsable o el tecnico del dispositivo (del token de la llamada gRPC) y la correlacion es el
 * codigo de la posesion ({@code PO-000012}): la noche entera agrupada bajo el.
 *
 * <p>Un solo agregado: el sujeto es siempre la posesion, por su id, con su codigo de etiqueta y,
 * en lo que hace un equipo (el acuse y la salida de via), el codigo del equipo detras. Del
 * {@code values} se guarda todo, como con mto-maintenance y mto-stock: el productor ya lo publica
 * por lista blanca y sin secretos, y el saneador del registro vuelve a pasarlo. Los turnos de la
 * posesion viajan enteros en {@code shifts}, con el id del turno de mto-maintenance, que es a lo
 * que enlazan las reglas.</p>
 *
 * <p>La gravedad es la del hecho: un desalojo emitido, un equipo que no acusa a tiempo y un acuse
 * que dice que no ({@code accepted == false}) son {@code CRITICAL}; una posesion cerrada a la
 * fuerza con equipos aun en la via ({@code forced}) es {@code WARNING}; lo demas, {@code INFO}.</p>
 */
@Service
@RequiredArgsConstructor
class FieldSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "field";
    static final String DEFAULT_ORIGIN = "mto-field";

    static final String POSSESSION = "possession";

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldSourceAdapter.class);

    private final ActivityIngestor activityIngestor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        DomainEventReader event = DomainEventReader.read(envelope, "Field");
        String type = event.type(ActivityCategory.FIELD);
        if (!ActivityTypes.isKnown(type)) {
            LOGGER.warn("Field event without a rule in the catalogue, recorded anyway: {}", type);
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
                .severity(severity(type, values))
                .occurredAt(event.occurredAt())
                .actor(event.actor())
                .subject(Subject.of(event.entityName(), event.entityId(), label(values)))
                .correlationId(event.correlationId())
                .payload(payload)
                .build());
    }

    /** El codigo de la posesion y, en lo que hace un equipo (el acuse, la salida de via), el del equipo detras. */
    static String label(PayloadReader values) {
        String code = values.string("code");
        String teamCode = values.string("teamCode");
        if (code != null && teamCode != null) {
            return code + " " + teamCode;
        }
        return code;
    }

    static ActivitySeverity severity(String type, PayloadReader values) {
        return switch (type) {
            case ActivityTypes.FIELD_POSSESSION_EVACUATION_ISSUED, ActivityTypes.FIELD_POSSESSION_EVACUATION_UNACKNOWLEDGED ->
                    ActivitySeverity.CRITICAL;
            case ActivityTypes.FIELD_POSSESSION_EVACUATION_ACKNOWLEDGED -> Boolean.FALSE.equals(values.booleanValue("accepted"))
                    ? ActivitySeverity.CRITICAL : ActivitySeverity.INFO;
            case ActivityTypes.FIELD_POSSESSION_CLOSED -> Boolean.TRUE.equals(values.booleanValue("forced"))
                    ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
            default -> ActivitySeverity.INFO;
        };
    }
}
