package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.ActivitySourceAdapter;
import com.alejandro.mtonotification.application.service.BurstAggregator;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.Subject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Los datos maestros de mto-configuration. Una importacion emite miles de eventos {@code profile}
 * y no puede ser miles de lineas: las altas y las modificaciones (y las bajas de lo que no importa
 * por si solo) se agregan en una rafaga por origen, entidad, operacion, actor y correlacion; las
 * bajas de via, estacion, perfil, seccionador y aislador y el alta de un paquete de ejecucion van
 * directas, con su aviso.
 */
@Service
@RequiredArgsConstructor
class MasterDataSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "master-data";
    static final String DEFAULT_ORIGIN = "mto-configuration";

    private final ActivityIngestor activityIngestor;
    private final BurstAggregator burstAggregator;
    private final NotificationProperties properties;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        PayloadReader data = PayloadReader.of(envelope.data());
        String entityName = normalize(data.string("entityName"));
        String operation = normalize(data.string("operation"));
        if (entityName == null) {
            throw new UnprocessableSourceEventException("Master data event has no entityName");
        }
        if (operation == null || !ActivityTypes.MASTER_DATA_OPERATIONS.contains(operation)) {
            throw new UnprocessableSourceEventException("Master data event has no known operation: " + data.string("operation"));
        }
        String entityId = data.string("entityId");
        String origin = envelope.origin() == null || envelope.origin().isBlank() ? DEFAULT_ORIGIN : envelope.origin().trim();
        Actor actor = envelope.actor() == null ? Actor.system() : envelope.actor().toActor();
        Instant occurredAt = envelope.creationDate() == null ? Instant.now() : envelope.creationDate();

        if (isDirect(entityName, operation)) {
            ingestDirect(envelope, data, origin, entityName, operation, entityId, actor, occurredAt);
        } else {
            burstAggregator.record(origin, entityName, operation, actor, envelope.correlationId(), entityId, occurredAt);
        }
    }

    boolean isDirect(String entityName, String operation) {
        NotificationProperties.Burst burst = properties.burst();
        Set<String> deletions = burst.directDeletions();
        Set<String> creations = burst.directCreations();
        return ("deleted".equals(operation) && deletions.contains(entityName))
                || ("created".equals(operation) && creations.contains(entityName));
    }

    private void ingestDirect(SourceEnvelope envelope, PayloadReader data, String origin, String entityName,
                              String operation, String entityId, Actor actor, Instant occurredAt) {
        String sourceEventId = envelope.operationId() != null ? envelope.operationId().toString()
                : ActivityTypes.masterDataType(entityName, operation) + ":" + entityId + ":" + occurredAt.toEpochMilli();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", entityName);
        payload.put("entityId", entityId);
        payload.put("operation", operation);
        PayloadReader values = data.nested("values");
        for (String key : new String[]{"code", "name", "profileId", "kp", "startKp", "endKp", "trackCode", "stationCode"}) {
            if (values.has(key)) {
                payload.put(key, values.string(key));
            }
        }
        String label = values.string("code") != null ? values.string("code") : values.string("name");

        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(origin, sourceEventId)
                .type(ActivityTypes.masterDataType(entityName, operation))
                .severity("deleted".equals(operation) ? ActivitySeverity.WARNING : ActivitySeverity.INFO)
                .occurredAt(occurredAt)
                .actor(actor)
                .subject(Subject.of(entityName, entityId, label))
                .correlationId(envelope.correlationId())
                .payload(payload)
                .build());
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
