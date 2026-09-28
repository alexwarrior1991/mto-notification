package com.alejandro.mtonotification.infrastructure.persistence.specification;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationAudience;
import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationReceipt;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * La bandeja de una persona: lo dirigido a alguna de sus claves de audiencia, con leida o no
 * leida resuelto por sus recibos y su marca de «todas leidas».
 */
public final class NotificationSpecification {

    private NotificationSpecification() {
    }

    /** {@code exists} sobre la audiencia, con {@code in} sobre las claves del token (una quincena a lo sumo). */
    public static Specification<Notification> visibleTo(Collection<String> audienceKeys) {
        if (audienceKeys == null || audienceKeys.isEmpty()) {
            return (root, query, builder) -> builder.disjunction();
        }
        return (root, query, builder) -> {
            Subquery<UUID> subquery = query.subquery(UUID.class);
            Root<NotificationAudience> audience = subquery.from(NotificationAudience.class);
            subquery.select(audience.get("id").get("notificationId"))
                    .where(builder.equal(audience.get("id").get("notificationId"), root.get("id")),
                            audience.get("id").get("audienceKey").in(audienceKeys));
            return builder.exists(subquery);
        };
    }

    /**
     * No leida: posterior a la marca de «todas leidas» (si la hay) y sin recibo. Leida es justo lo
     * contrario; el recibo gana a la marca.
     */
    public static Specification<Notification> unreadBy(String username, Instant allReadUntil, boolean unread) {
        return (root, query, builder) -> {
            Subquery<UUID> receipts = query.subquery(UUID.class);
            Root<NotificationReceipt> receipt = receipts.from(NotificationReceipt.class);
            receipts.select(receipt.get("id").get("notificationId"))
                    .where(builder.equal(receipt.get("id").get("notificationId"), root.get("id")),
                            builder.equal(receipt.get("id").get("username"), username));
            Predicate hasReceipt = builder.exists(receipts);
            Predicate afterMark = allReadUntil == null
                    ? builder.conjunction()
                    : builder.greaterThan(root.get("createdAt"), allReadUntil);
            Predicate isUnread = builder.and(afterMark, builder.not(hasReceipt));
            return unread ? isUnread : builder.not(isUnread);
        };
    }

    public static Specification<Notification> categoryEquals(ActivityCategory category) {
        return SpecificationUtils.equalsValue("category", category);
    }

    public static Specification<Notification> severityEquals(ActivitySeverity severity) {
        return SpecificationUtils.equalsValue("severity", severity);
    }

    public static Specification<Notification> createdBetween(Instant from, Instant to) {
        return SpecificationUtils.between("createdAt", from, to);
    }
}
