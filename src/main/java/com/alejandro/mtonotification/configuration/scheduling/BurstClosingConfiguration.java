package com.alejandro.mtonotification.configuration.scheduling;

import com.alejandro.mtonotification.application.service.BurstAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Cierra las rafagas que tocan cada {@code app.notification.burst.close-fixed-delay}. Los tests lo apagan y lo llaman. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.notification.burst", name = "close-enabled", havingValue = "true", matchIfMissing = true)
public class BurstClosingConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(BurstClosingConfiguration.class);

    private final BurstAggregator burstAggregator;

    public BurstClosingConfiguration(BurstAggregator burstAggregator) {
        this.burstAggregator = burstAggregator;
    }

    @Scheduled(fixedDelayString = "${app.notification.burst.close-fixed-delay:PT10S}", initialDelayString = "PT10S")
    public void closeExpiredBursts() {
        try {
            burstAggregator.closeExpired();
        } catch (RuntimeException failure) {
            LOGGER.error("Closing master data bursts failed; it will run again on the next tick", failure);
        }
    }
}
