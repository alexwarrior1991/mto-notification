package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;

import java.util.List;
import java.util.UUID;

/**
 * Escribe una notificacion con sus audiencias y, para los canales que empujan, una entrega
 * AUDIENCE por audiencia. Lo usan el motor de reglas y los avisos manuales.
 */
public interface NotificationFactory {

    Notification create(NotificationDraft draft);

    /**
     * @param ruleKey   la regla, o {@code manual-broadcast} / {@code test-email}
     * @param channels  {@code inbox} y/o {@code email}; ya validados
     */
    record NotificationDraft(
            String ruleKey,
            UUID activityEventId,
            ActivityCategory category,
            ActivitySeverity severity,
            String title,
            String body,
            String link,
            String subjectType,
            String subjectId,
            List<Audience> audiences,
            List<String> channels
    ) {
    }
}
