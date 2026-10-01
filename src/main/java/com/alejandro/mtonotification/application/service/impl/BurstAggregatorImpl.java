package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.BurstAggregator;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.Fingerprints;
import com.alejandro.mtonotification.domain.model.Subject;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityBurst;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityBurstRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Las rafagas. {@link #record} corre dentro de la transaccion del inbox (un upsert);
 * {@link #closeExpired} la abre el planificador: reclama con {@code for update skip locked}, cierra
 * y escribe UNA linea por rafaga, todo en una transaccion. Solo un cerrador gana cada rafaga, y un
 * consumidor que esperaba en la fila bloqueada la encuentra cerrada y abre otra: nada se pierde.
 * La de un trabajo (con {@code correlationId}) se cierra cuando el trabajo deja de mandar, no a los
 * diez minutos: una importacion de once es una linea, no dos.
 */
@Service
@RequiredArgsConstructor
class BurstAggregatorImpl implements BurstAggregator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BurstAggregatorImpl.class);

    static final String SELF_SOURCE = "mto-notification";
    private static final int CLOSE_LIMIT = 100;

    private final ActivityBurstRepository activityBurstRepository;
    private final ActivityIngestor activityIngestor;
    private final NotificationProperties properties;
    private final JsonPayloads jsonPayloads;

    @Override
    public void record(String sourceService, String entityName, String operation, Actor actor, String correlationId,
                       String sampleId, Instant eventAt) {
        Actor who = actor == null ? Actor.system() : actor;
        String key = Fingerprints.canonical(sourceService, entityName, operation,
                who.username() == null ? who.kind().name() : who.username(), correlationId);
        activityBurstRepository.recordEvent(key, sourceService, entityName, operation, who.kind().name(), who.username(),
                who.id(), correlationId, sampleId, properties.burst().sampleSize(), eventAt == null ? Instant.now() : eventAt);
    }

    @Override
    @Transactional
    public int closeExpired() {
        NotificationProperties.Burst burst = properties.burst();
        Instant now = Instant.now();
        List<ActivityBurst> expired = activityBurstRepository.findExpiredOpenForUpdate(
                now.minus(burst.idleTimeout()), now.minus(burst.maxWindow()), now.minus(burst.correlatedMaxWindow()),
                CLOSE_LIMIT);
        int closed = 0;
        for (ActivityBurst open : expired) {
            if (activityBurstRepository.close(open.getId()) == 0) {
                continue;
            }
            closed++;
            ingestClosed(open);
        }
        if (closed > 0) {
            LOGGER.info("Closed {} master data burst(s)", closed);
        }
        return closed;
    }

    private void ingestClosed(ActivityBurst burst) {
        List<String> sample = jsonPayloads.readStringList(burst.getSampleIds());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", burst.getEntityName());
        payload.put("operation", burst.getOperation());
        payload.put("eventCount", burst.getEventCount());
        payload.put("sampleIds", sample);
        payload.put("openedAt", burst.getOpenedAt().toString());
        payload.put("lastEventAt", burst.getLastEventAt().toString());

        Actor actor = new Actor(burst.getActorKind(), burst.getActorUsername(), burst.getActorId());
        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(SELF_SOURCE, "burst:" + burst.getId())
                .type(ActivityTypes.masterDataType(burst.getEntityName(), burst.getOperation()))
                .severity(ActivitySeverity.INFO)
                .occurredAt(burst.getLastEventAt())
                .actor(actor)
                .subject(burst.getEventCount() == 1 && !sample.isEmpty()
                        ? Subject.of(burst.getEntityName(), sample.getFirst())
                        : Subject.of(burst.getEntityName(), null, burst.getEventCount() + " " + burst.getEntityName()))
                .correlationId(burst.getCorrelationId())
                .eventCount(burst.getEventCount())
                .payload(payload)
                .build());
    }
}
