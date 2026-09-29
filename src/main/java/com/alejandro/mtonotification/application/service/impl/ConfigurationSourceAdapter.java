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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lo que mto-configuration cuenta de si mismo por {@code mto.configuration.exchange}: hoy, el
 * final de un trabajo en segundo plano ({@code configuration.job.finished}), una linea por trabajo
 * con sus recuentos, para avisar a quien lo lanzo. Un evento que este servicio aun no conoce se
 * registra igualmente con su tipo y sin valores: el registro no pierde nada por llegar antes que
 * su regla.
 */
@Service
@RequiredArgsConstructor
class ConfigurationSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "configuration";
    static final String DEFAULT_ORIGIN = "mto-configuration";
    static final String JOB_ENTITY = "job";
    static final String FINISHED_EVENT = "finished";

    /** Un trabajo que no acabo bien es un aviso, no una noticia. */
    static final Set<String> WARNING_STATUSES = Set.of("FAILED", "COMPLETED_WITH_ERRORS");

    /** Lo que se guarda de un trabajo: sus recuentos y su ficha, nunca el detalle de errores por elemento (no viaja). */
    static final List<String> JOB_KEYS = List.of("jobId", "type", "status", "createdBy", "createdAt", "startedAt", "finishedAt",
            "totalItems", "processedItems", "successfulItems", "failedItems", "fileName", "trackId", "mapperType", "errorMessage");

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationSourceAdapter.class);

    private final ActivityIngestor activityIngestor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        DomainEventReader event = DomainEventReader.read(envelope, "Configuration");
        if (JOB_ENTITY.equals(event.entityName()) && FINISHED_EVENT.equals(event.eventName())) {
            ingestJobFinished(event);
        } else {
            ingestUnknown(event);
        }
    }

    private void ingestJobFinished(DomainEventReader event) {
        PayloadReader values = event.values();
        String jobId = values.string("jobId") != null ? values.string("jobId") : event.entityId();
        String status = values.string("status") == null ? null : values.string("status").trim().toUpperCase(Locale.ROOT);

        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : JOB_KEYS) {
            Object value = values.all().get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }

        Actor actor = event.actor();
        if (actor.kind() == com.alejandro.mtonotification.domain.model.ActorKind.SYSTEM && values.string("createdBy") != null) {
            // Un trabajo antiguo (sin actor en el sobre) sigue teniendo a quien lo lanzo.
            actor = Actor.ofUsername(values.string("createdBy"), null);
        }

        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(event.origin(DEFAULT_ORIGIN), event.sourceEventId())
                .type(ActivityTypes.CONFIGURATION_JOB_FINISHED)
                .severity(status != null && WARNING_STATUSES.contains(status) ? ActivitySeverity.WARNING : ActivitySeverity.INFO)
                .occurredAt(finishedAt(values.string("finishedAt"), event.occurredAt()))
                .actor(actor)
                .subject(Subject.of(JOB_ENTITY, jobId, values.string("type")))
                .correlationId(event.correlationId() != null ? event.correlationId() : jobId)
                .payload(payload)
                .build());
    }

    private void ingestUnknown(DomainEventReader event) {
        String type = event.type(ActivityCategory.CONFIGURATION);
        LOGGER.warn("Configuration event without a dedicated adapter, recorded as {} without values: entityId={}", type, event.entityId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", event.entityName());
        payload.put("entityId", event.entityId());
        payload.put("eventName", event.eventName());
        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(event.origin(DEFAULT_ORIGIN), event.sourceEventId())
                .type(type)
                .severity(ActivitySeverity.INFO)
                .occurredAt(event.occurredAt())
                .actor(event.actor())
                .subject(Subject.of(event.entityName(), event.entityId()))
                .correlationId(event.correlationId())
                .payload(payload)
                .build());
    }

    private static Instant finishedAt(String value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException unreadable) {
            return fallback;
        }
    }
}
