package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.DeliveryRelayService;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Subject;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.repository.DeliveryRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Las transacciones cortas del despachador. Cada metodo es una; el envio queda entre dos. */
@Service
@RequiredArgsConstructor
class DeliveryRelayServiceImpl implements DeliveryRelayService {

    static final String SELF_SOURCE = "mto-notification";
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;
    private final ActivityIngestor activityIngestor;
    private final NotificationProperties properties;

    @Override
    @Transactional
    public List<Delivery> claimDue(int batchSize) {
        Instant now = Instant.now();
        List<UUID> ids = deliveryRepository.findDueIdsForUpdate(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        deliveryRepository.claim(ids, now.plus(properties.delivery().claimVisibilityTimeout()));
        return deliveryRepository.findAllById(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Notification> loadNotification(Delivery delivery) {
        return notificationRepository.findById(delivery.getNotificationId());
    }

    @Override
    @Transactional
    public int expand(Delivery audienceDelivery, List<Recipient> recipients) {
        int inserted = 0;
        int maxAttempts = properties.delivery().maxAttempts();
        for (Recipient recipient : recipients) {
            if (recipient.hasAddress()) {
                inserted += deliveryRepository.insertRecipientIfMissing(audienceDelivery.getNotificationId(),
                        audienceDelivery.getChannel(), recipient.address().trim(), recipient.username(),
                        DeliveryStatus.PENDING.name(), maxAttempts, null);
            } else {
                inserted += deliveryRepository.insertRecipientIfMissing(audienceDelivery.getNotificationId(),
                        audienceDelivery.getChannel(), recipient.username(), recipient.username(),
                        DeliveryStatus.SKIPPED.name(), maxAttempts, "no address for channel " + audienceDelivery.getChannel());
            }
        }
        deliveryRepository.markSent(audienceDelivery.getId());
        return inserted;
    }

    @Override
    @Transactional
    public void markSent(Delivery delivery) {
        deliveryRepository.markSent(delivery.getId());
    }

    @Override
    @Transactional
    public void reschedule(Delivery delivery, Instant nextAttemptAt, String error) {
        deliveryRepository.reschedule(delivery.getId(), nextAttemptAt, truncate(error));
    }

    @Override
    @Transactional
    public void markDead(Delivery delivery, String error) {
        deliveryRepository.settle(delivery.getId(), DeliveryStatus.FAILED.name(), truncate(error));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveryId", delivery.getId().toString());
        payload.put("notificationId", delivery.getNotificationId().toString());
        payload.put("channel", delivery.getChannel());
        payload.put("attempts", delivery.getAttempts());
        payload.put("error", truncate(error));
        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(SELF_SOURCE, "delivery-dead:" + delivery.getId())
                .type(ActivityTypes.SYSTEM_DELIVERY_DEAD)
                .severity(ActivitySeverity.WARNING)
                .occurredAt(Instant.now())
                .subject(Subject.of("delivery", delivery.getId().toString()))
                .payload(payload)
                .build());
    }

    @Override
    @Transactional
    public void markSkipped(Delivery delivery, String reason) {
        deliveryRepository.settle(delivery.getId(), DeliveryStatus.SKIPPED.name(), truncate(reason));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOverHourlyLimit(String channel, String address, int maxPerHour) {
        return deliveryRepository.countSentToRecipientSince(channel, address, Instant.now().minus(Duration.ofHours(1))) >= maxPerHour;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
