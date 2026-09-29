package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.ActivitySourceAdapter;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.Subject;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lo que mto-stock cuenta de si mismo ({@code mto.stock.<entidad>.<evento>},
 * {@code docs/06-messaging.md} de ese repositorio): un material cuyo disponible total acaba de
 * cruzar por debajo de su minimo, una reserva cancelada o liberada y un ajuste de inventario, con
 * quien lo hizo (una persona del almacen, o la cuenta de servicio con la que mto-maintenance reserva
 * y libera material) y la correlacion de la peticion. El tipo sale del nombre del evento y el sujeto
 * es el material por su codigo, la reserva por su material y su proyecto, el ajuste por su material.
 *
 * <p>Del {@code values} se guarda todo, como con mto-maintenance: el productor ya lo publica por
 * lista blanca y sin secretos, y el saneador del registro vuelve a pasarlo. Las claves del material,
 * el almacen y el proyecto son las mismas en los cuatro eventos, asi que una regla las lee igual.</p>
 *
 * <p>La gravedad es la del hecho: un material bajo minimo, una reserva tocada por alguien distinto
 * de quien la creo ({@code createdBy} frente al actor) y un ajuste negativo son {@code WARNING}; lo
 * demas es {@code INFO}. La regla decide a quien avisar y, con el umbral de sus variables, si un
 * ajuste merece aviso.</p>
 */
@Service
@RequiredArgsConstructor
class StockSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "stock";
    static final String DEFAULT_ORIGIN = "mto-stock";

    static final String MATERIAL = "material";
    static final String RESERVATION = "reservation";
    static final String ADJUSTMENT = "adjustment";

    static final String NEGATIVE = "NEGATIVE";

    private static final Logger LOGGER = LoggerFactory.getLogger(StockSourceAdapter.class);

    private final ActivityIngestor activityIngestor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        DomainEventReader event = DomainEventReader.read(envelope, "Stock");
        String type = event.type(ActivityCategory.STOCK);
        if (!ActivityTypes.isKnown(type)) {
            LOGGER.warn("Stock event without a rule in the catalogue, recorded anyway: {}", type);
        }
        PayloadReader values = event.values();
        Actor actor = event.actor();

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
                .severity(severity(type, actor, values))
                .occurredAt(event.occurredAt())
                .actor(actor)
                .subject(Subject.of(event.entityName(), event.entityId(), label(event.entityName(), values)))
                .correlationId(event.correlationId())
                .payload(payload)
                .build());
    }

    /** El codigo del material; la reserva, ademas, con el proyecto para el que se hizo. */
    static String label(String entityName, PayloadReader values) {
        String material = values.string("materialCode");
        if (RESERVATION.equals(entityName) && material != null && values.string("projectCode") != null) {
            return material + " " + values.string("projectCode");
        }
        return material;
    }

    static ActivitySeverity severity(String type, Actor actor, PayloadReader values) {
        return switch (type) {
            case ActivityTypes.STOCK_MATERIAL_BELOW_MINIMUM -> ActivitySeverity.WARNING;
            case ActivityTypes.STOCK_RESERVATION_CANCELLED, ActivityTypes.STOCK_RESERVATION_RELEASED ->
                    touchedBySomebodyElse(actor, values.string("createdBy")) ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
            case ActivityTypes.STOCK_ADJUSTMENT_REGISTERED -> NEGATIVE.equals(upper(values.string("direction")))
                    ? ActivitySeverity.WARNING : ActivitySeverity.INFO;
            default -> ActivitySeverity.INFO;
        };
    }

    /** Quien la creo es lo que dice el evento; quien la toca, el actor del sobre. Sin uno de los dos no se sabe, y no se alarma. */
    private static boolean touchedBySomebodyElse(Actor actor, String createdBy) {
        return createdBy != null && actor.username() != null && !createdBy.equals(actor.username());
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
