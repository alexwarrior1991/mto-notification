package com.alejandro.mtonotification.application.dto.notification;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Una notificacion con sus audiencias, tal como la ve la administracion. */
public record NotificationResponse(
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
        List<String> audiences,
        Instant createdAt,
        String createdBy
) {
}
