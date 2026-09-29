package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.RetentionPurge;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityBurstRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.InboxMessageRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.RuleThrottleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * La purga: cada tabla con su retencion, por lotes y cada lote en su transaccion, con un tope de
 * lotes por pasada para que una tabla muy atrasada no bloquee la noche entera. Keycloak caduca
 * sus eventos a los 7 dias: este servicio es el archivo, y por eso los accesos duran 90.
 */
@Service
class RetentionPurgeImpl implements RetentionPurge {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionPurgeImpl.class);

    private final ActivityEventRepository activityEventRepository;
    private final NotificationRepository notificationRepository;
    private final InboxMessageRepository inboxMessageRepository;
    private final ActivityBurstRepository burstRepository;
    private final RuleThrottleRepository throttleRepository;
    private final TransactionTemplate transactionTemplate;
    private final NotificationProperties properties;

    RetentionPurgeImpl(ActivityEventRepository activityEventRepository, NotificationRepository notificationRepository,
                       InboxMessageRepository inboxMessageRepository, ActivityBurstRepository burstRepository,
                       RuleThrottleRepository throttleRepository, TransactionTemplate transactionTemplate,
                       NotificationProperties properties) {
        this.activityEventRepository = activityEventRepository;
        this.notificationRepository = notificationRepository;
        this.inboxMessageRepository = inboxMessageRepository;
        this.burstRepository = burstRepository;
        this.throttleRepository = throttleRepository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Override
    public Map<String, Integer> purge() {
        NotificationProperties.Retention retention = properties.retention();
        Instant now = Instant.now();
        int batch = retention.batchSize();
        Map<String, Integer> deleted = new LinkedHashMap<>();

        deleted.put("activity_event." + ActivityCategory.ACCESS, purgeCategory(ActivityCategory.ACCESS, retention.activity().access(), now, batch));
        deleted.put("activity_event." + ActivityCategory.USERS, purgeCategory(ActivityCategory.USERS, retention.activity().users(), now, batch));
        deleted.put("activity_event." + ActivityCategory.CONFIGURATION, purgeCategory(ActivityCategory.CONFIGURATION, retention.activity().configuration(), now, batch));
        deleted.put("activity_event." + ActivityCategory.MAINTENANCE, purgeCategory(ActivityCategory.MAINTENANCE, retention.activity().maintenance(), now, batch));
        deleted.put("activity_event." + ActivityCategory.STOCK, purgeCategory(ActivityCategory.STOCK, retention.activity().stock(), now, batch));
        deleted.put("activity_event." + ActivityCategory.SYSTEM, purgeCategory(ActivityCategory.SYSTEM, retention.activity().system(), now, batch));
        deleted.put("notification", loop(() -> notificationRepository.deleteCreatedBefore(now.minus(retention.notifications()), batch)));
        deleted.put("inbox_message", loop(() -> inboxMessageRepository.deleteProcessedBefore(now.minus(retention.inbox()), batch)));
        deleted.put("activity_burst", loop(() -> burstRepository.deleteClosedBefore(now.minus(retention.bursts()), batch)));
        deleted.put("rule_throttle", loop(() -> throttleRepository.deleteExpiredBefore(now.minus(retention.throttles()), batch)));

        LOGGER.info("Retention purge done: {}", deleted);
        return deleted;
    }

    private int purgeCategory(ActivityCategory category, Duration retention, Instant now, int batch) {
        Instant before = now.minus(retention);
        return loop(() -> activityEventRepository.deleteByCategoryBefore(category.name(), before, batch));
    }

    private int loop(IntSupplier batchDelete) {
        int total = 0;
        for (int round = 0; round < properties.retention().maxBatchesPerRun(); round++) {
            Integer deleted = transactionTemplate.execute(status -> batchDelete.getAsInt());
            if (deleted == null || deleted == 0) {
                break;
            }
            total += deleted;
        }
        return total;
    }
}
