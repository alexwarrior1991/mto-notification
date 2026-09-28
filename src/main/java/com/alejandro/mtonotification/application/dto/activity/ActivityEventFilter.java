package com.alejandro.mtonotification.application.dto.activity;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Instant;

/** Los filtros de {@code GET /activity}. {@code ACCESS} no cabe: tiene su endpoint. */
public record ActivityEventFilter(
        ActivityCategory category,
        String type,
        String actorUsername,
        String subjectType,
        String subjectId,
        ActivitySeverity severity,
        String sourceService,
        Instant from,
        Instant to,
        boolean includeSuperseded
) {
}
