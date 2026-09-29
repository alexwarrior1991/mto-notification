package com.alejandro.mtonotification.application.dto.access;

import java.time.Instant;

/** Los filtros de {@code GET /access}. */
public record AccessEventFilter(
        String username,
        String ipAddress,
        String type,
        AccessOutcome outcome,
        Instant from,
        Instant to
) {
}
