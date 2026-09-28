package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/** Espera creciente entre intentos, con tope y algo de azar para que las entregas no se agolpen. */
@Component
public class DeliveryRetryPolicy {

    private final NotificationProperties properties;

    public DeliveryRetryPolicy(NotificationProperties properties) {
        this.properties = properties;
    }

    /** @param attempts intentos ya hechos (el primero es 1) */
    public Instant nextAttemptAt(int attempts, Instant now) {
        NotificationProperties.Delivery delivery = properties.delivery();
        long initial = Math.max(1, delivery.initialRetryDelay().toMillis());
        long max = Math.max(initial, delivery.maxRetryDelay().toMillis());
        int exponent = Math.max(0, Math.min(attempts - 1, 20));
        long delay = Math.min(max, initial * (1L << exponent));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 4));
        return now.plus(Duration.ofMillis(delay + jitter));
    }
}
