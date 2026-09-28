package com.alejandro.mtonotification.configuration.keycloak;

import com.alejandro.mtonotification.application.service.KeycloakEventsPoller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sondea Keycloak cada {@code app.keycloak.events.poll-interval}. Con {@code enabled=false} no
 * existe: los tests lo apagan y llaman al lector a mano.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.keycloak.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KeycloakPollingConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakPollingConfiguration.class);

    private final KeycloakEventsPoller poller;

    public KeycloakPollingConfiguration(KeycloakEventsPoller poller, KeycloakProperties properties) {
        this.poller = poller;
        LOGGER.info("Keycloak events reader enabled: every {}, login={}, admin={}, page={}, overlap={}, lookback={}",
                properties.events().pollInterval(), properties.events().loginEnabled(), properties.events().adminEnabled(),
                properties.events().pageSize(), properties.events().overlap(), properties.events().initialLookback());
    }

    @Scheduled(fixedDelayString = "${app.keycloak.events.poll-interval:PT20S}", initialDelayString = "${app.keycloak.events.initial-delay:PT15S}")
    public void poll() {
        try {
            poller.pollOnce();
        } catch (RuntimeException failure) {
            LOGGER.error("Keycloak polling failed unexpectedly; it will run again on the next tick", failure);
        }
    }
}
