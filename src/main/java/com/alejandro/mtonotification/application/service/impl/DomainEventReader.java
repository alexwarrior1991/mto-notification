package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;

import java.time.Instant;
import java.util.Locale;

/**
 * Lectura del {@code data} en forma {@code DomainEvent} ({@code entityName}, {@code entityId},
 * {@code eventName}, {@code values}) que publican los servicios sobre lo suyo (mto-configuration
 * con sus trabajos, mto-users con sus acciones). Sin entidad o sin evento no hay tipo que dar a
 * la linea, y eso es permanente: a la DLQ sin reintentos. Lo demas se lee con tolerancia.
 */
final class DomainEventReader {

    private final SourceEnvelope envelope;
    private final PayloadReader data;
    private final String entityName;
    private final String eventName;

    private DomainEventReader(SourceEnvelope envelope, PayloadReader data, String entityName, String eventName) {
        this.envelope = envelope;
        this.data = data;
        this.entityName = entityName;
        this.eventName = eventName;
    }

    static DomainEventReader read(SourceEnvelope envelope, String sourceLabel) {
        PayloadReader data = PayloadReader.of(envelope.data());
        String entityName = normalize(data.string("entityName"));
        String eventName = normalize(data.string("eventName"));
        if (entityName == null) {
            throw new UnprocessableSourceEventException(sourceLabel + " event has no entityName");
        }
        if (eventName == null) {
            throw new UnprocessableSourceEventException(sourceLabel + " event has no eventName");
        }
        return new DomainEventReader(envelope, data, entityName, eventName);
    }

    String entityName() {
        return entityName;
    }

    String eventName() {
        return eventName;
    }

    String entityId() {
        return data.string("entityId");
    }

    PayloadReader values() {
        return data.nested("values");
    }

    String origin(String defaultOrigin) {
        return envelope.origin() == null || envelope.origin().isBlank() ? defaultOrigin : envelope.origin().trim();
    }

    /** El actor del sobre; sin el, el sistema (dicho, no callado). */
    Actor actor() {
        return envelope.actor() == null ? Actor.system() : envelope.actor().toActor();
    }

    Instant occurredAt() {
        return envelope.creationDate() == null ? Instant.now() : envelope.creationDate();
    }

    String correlationId() {
        return envelope.correlationId();
    }

    /** El {@code operationId} del sobre, que es la clave de idempotencia; si faltara, algo estable a partir del evento. */
    String sourceEventId() {
        if (envelope.operationId() != null) {
            return envelope.operationId().toString();
        }
        return entityName + "." + eventName + ":" + entityId() + ":" + occurredAt().toEpochMilli();
    }

    /** {@code <categoria>.<entidad>.<evento>}: el tipo de la linea sale del nombre del evento, como la clave de enrutado. */
    String type(ActivityCategory category) {
        try {
            return ActivityTypes.requireWellFormed(category.typePrefix() + entityName + "." + eventName);
        } catch (IllegalArgumentException malformed) {
            throw new UnprocessableSourceEventException("Event " + entityName + "." + eventName + " does not make a valid activity type: " + malformed.getMessage());
        }
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return normalized.isEmpty() ? null : normalized;
    }
}
