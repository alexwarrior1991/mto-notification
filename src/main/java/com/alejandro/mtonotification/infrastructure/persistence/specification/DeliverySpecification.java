package com.alejandro.mtonotification.infrastructure.persistence.specification;

import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** Los filtros de la administracion de entregas. */
public final class DeliverySpecification {

    private DeliverySpecification() {
    }

    public static Specification<Delivery> statusEquals(DeliveryStatus status) {
        return SpecificationUtils.equalsValue("status", status);
    }

    public static Specification<Delivery> channelEquals(String channel) {
        return SpecificationUtils.equalsIgnoreCase("channel", channel);
    }

    public static Specification<Delivery> notificationIdEquals(UUID notificationId) {
        return SpecificationUtils.equalsValue("notificationId", notificationId);
    }
}
