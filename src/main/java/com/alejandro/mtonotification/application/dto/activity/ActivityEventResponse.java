package com.alejandro.mtonotification.application.dto.activity;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Una linea del registro. {@code payload} solo viene en el detalle. */
public record ActivityEventResponse(
        UUID id,
        Long seq,
        String sourceService,
        String sourceEventId,
        ActivityCategory category,
        String type,
        ActivitySeverity severity,
        Instant occurredAt,
        Instant recordedAt,
        ActorResponse actor,
        SubjectResponse subject,
        String correlationId,
        int eventCount,
        Map<String, Object> payload,
        UUID supersededBy
) {
}
