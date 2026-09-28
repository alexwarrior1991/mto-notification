package com.alejandro.mtonotification.configuration.scheduling;

import com.alejandro.mtonotification.application.service.RetentionPurge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** La purga, una vez al dia ({@code app.notification.retention.cron}). Los tests la apagan y la llaman. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.notification.retention", name = "purge-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionPurgeConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionPurgeConfiguration.class);

    private final RetentionPurge retentionPurge;

    public RetentionPurgeConfiguration(RetentionPurge retentionPurge) {
        this.retentionPurge = retentionPurge;
    }

    @Scheduled(cron = "${app.notification.retention.cron:0 17 3 * * *}")
    public void purge() {
        try {
            retentionPurge.purge();
        } catch (RuntimeException failure) {
            LOGGER.error("Retention purge failed; it will run again tomorrow", failure);
        }
    }
}
