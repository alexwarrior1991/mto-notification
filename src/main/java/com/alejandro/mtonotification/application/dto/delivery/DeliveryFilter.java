package com.alejandro.mtonotification.application.dto.delivery;

import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;

import java.util.UUID;

/** Los filtros de {@code GET /admin/deliveries}. */
public record DeliveryFilter(DeliveryStatus status, String channel, UUID notificationId) {
}
