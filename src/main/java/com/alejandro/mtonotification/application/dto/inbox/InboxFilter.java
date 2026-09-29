package com.alejandro.mtonotification.application.dto.inbox;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Instant;

/** Los filtros de {@code GET /inbox}. {@code unread} a {@code null} trae todo. */
public record InboxFilter(Boolean unread, ActivityCategory category, ActivitySeverity severity, Instant from, Instant to) {
}
