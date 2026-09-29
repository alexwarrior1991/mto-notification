package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.DeliveryDispatchTrigger;
import com.alejandro.mtonotification.application.service.NotificationFactory;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.domain.model.DeliveryChannels;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationAudience;
import com.alejandro.mtonotification.infrastructure.persistence.repository.DeliveryRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationAudienceRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Escribe la notificacion, sus audiencias y, para los canales que empujan, una entrega AUDIENCE
 * por audiencia; despues pide un despacho para cuando se confirme la transaccion. No resuelve
 * miembros: eso es del despachador, fuera de esta transaccion.
 */
@Service
@RequiredArgsConstructor
class NotificationFactoryImpl implements NotificationFactory {

    private final NotificationRepository notificationRepository;
    private final NotificationAudienceRepository notificationAudienceRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryDispatchTrigger dispatchTrigger;
    private final NotificationProperties properties;

    @Override
    @Transactional
    public Notification create(NotificationDraft draft) {
        Notification notification = notificationRepository.saveAndFlush(Notification.builder()
                .ruleKey(draft.ruleKey())
                .activityEventId(draft.activityEventId())
                .category(draft.category())
                .severity(draft.severity())
                .title(draft.title())
                .body(draft.body())
                .link(draft.link())
                .subjectType(draft.subjectType())
                .subjectId(draft.subjectId())
                .build());

        List<NotificationAudience> audiences = new ArrayList<>();
        for (Audience audience : draft.audiences()) {
            audiences.add(new NotificationAudience(
                    new NotificationAudience.Id(notification.getId(), audience.toKey()),
                    audience.kind().name(), notification.getCreatedAt()));
        }
        notificationAudienceRepository.saveAllAndFlush(audiences);

        boolean pushed = false;
        for (String channel : draft.channels()) {
            if (!DeliveryChannels.isPushed(channel)) {
                continue;
            }
            for (Audience audience : draft.audiences()) {
                pushed |= deliveryRepository.insertAudienceIfMissing(notification.getId(), channel,
                        audience.kind().name(), audience.toKey(), properties.delivery().maxAttempts()) > 0;
            }
        }
        if (pushed) {
            dispatchTrigger.requestDispatchAfterCommit();
        }
        return notification;
    }
}
