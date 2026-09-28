package com.alejandro.mtonotification.application.dto.delivery;

import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

/** Una entrega, para la administracion. */
public record DeliveryResponse(
        UUID id,
        UUID notificationId,
        String channel,
        DeliveryScope scope,
        String audienceKey,
        String recipient,
        String recipientUsername,
        DeliveryStatus status,
        int attempts,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant sentAt,
        String lastError,
        Instant createdAt
) {
}
