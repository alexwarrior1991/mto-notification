package com.alejandro.mtonotification.configuration.scheduling;

import com.alejandro.mtonotification.application.service.DeliveryDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * El despacho programado: recoge lo que el despacho inmediato no pudo (la instancia que lo pidio
 * murio, un reintento que vencio). Con {@code delivery.enabled=false} no hay despacho de ningun
 * tipo y las entregas se acumulan PENDING.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.notification.delivery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryDispatchConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryDispatchConfiguration.class);

    private final DeliveryDispatcher dispatcher;

    public DeliveryDispatchConfiguration(DeliveryDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${app.notification.delivery.fixed-delay:PT15S}", initialDelayString = "${app.notification.delivery.initial-delay:PT20S}")
    public void dispatchDueDeliveries() {
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException failure) {
            LOGGER.error("Dispatching deliveries failed; it will run again on the next tick", failure);
        }
    }
}
