package com.alejandro.mtonotification.application.dto.access;

import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Un acceso: la linea del registro con su usuario, su IP y como acabo. */
public record AccessEventResponse(
        UUID id,
        Long seq,
        String type,
        ActivitySeverity severity,
        AccessOutcome outcome,
        Instant occurredAt,
        Instant recordedAt,
        String username,
        String userId,
        String ipAddress,
        String correlationId,
        int eventCount,
        Map<String, Object> payload
) {
}
