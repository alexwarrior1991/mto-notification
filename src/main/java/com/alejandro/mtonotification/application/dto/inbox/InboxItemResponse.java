package com.alejandro.mtonotification.application.dto.inbox;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Instant;
import java.util.UUID;

/** Una notificacion tal como la ve la persona: con su leida o no leida. */
public record InboxItemResponse(
        UUID id,
        String ruleKey,
        ActivityCategory category,
        ActivitySeverity severity,
        String title,
        String body,
        String link,
        String subjectType,
        String subjectId,
        UUID activityEventId,
        Instant createdAt,
        boolean read,
        Instant readAt
) {
}
