package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import com.alejandro.mtonotification.application.service.AudienceResolver;
import com.alejandro.mtonotification.application.service.DeliveryChannel;
import com.alejandro.mtonotification.application.service.DeliveryDispatcher;
import com.alejandro.mtonotification.application.service.DeliveryRelayService;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.domain.model.DeliveryChannels;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El despachador. Sin transaccion propia: reclama (una transaccion), expande o envia (la red), y
 * marca (otra transaccion). Una AUDIENCE se expande en RECIPIENT, que entran en la siguiente
 * vuelta del mismo lote; un fallo del directorio o del canal se reintenta con espera creciente y,
 * agotados los intentos, la entrega muere y el registro lo cuenta.
 */
@Service
class DeliveryDispatcherImpl implements DeliveryDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryDispatcherImpl.class);

    /** Vueltas por lote: lo justo para que una audiencia recien expandida salga en el mismo lote. */
    private static final int MAX_ROUNDS = 5;

    private final DeliveryRelayService relay;
    private final AudienceResolver audienceResolver;
    private final Map<String, DeliveryChannel> channels;
    private final DeliveryRetryPolicy retryPolicy;
    private final NotificationProperties properties;

    DeliveryDispatcherImpl(DeliveryRelayService relay, AudienceResolver audienceResolver, List<DeliveryChannel> channels,
                           DeliveryRetryPolicy retryPolicy, NotificationProperties properties) {
        this.relay = relay;
        this.audienceResolver = audienceResolver;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        Map<String, DeliveryChannel> index = new LinkedHashMap<>();
        for (DeliveryChannel channel : channels) {
            String key = channel.channel().trim().toLowerCase();
            if (!DeliveryChannels.isPushed(key)) {
                throw new IllegalStateException(channel.getClass().getName() + " claims an unknown or non-pushed channel '" + key + "'");
            }
            if (index.put(key, channel) != null) {
                throw new IllegalStateException("Two beans implement the delivery channel '" + key + "'");
            }
        }
        this.channels = Map.copyOf(index);
        LOGGER.info("Delivery dispatcher ready: channels {}", index.keySet());
    }

    @Override
    public DispatchSummary dispatchDue() {
        int claimed = 0;
        int expanded = 0;
        int sent = 0;
        int rescheduled = 0;
        int dead = 0;
        int skipped = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Delivery> batch = relay.claimDue(properties.delivery().batchSize());
            if (batch.isEmpty()) {
                break;
            }
            claimed += batch.size();
            for (Delivery delivery : batch) {
                Outcome outcome = handle(delivery);
                switch (outcome) {
                    case EXPANDED -> expanded++;
                    case SENT -> sent++;
                    case RESCHEDULED -> rescheduled++;
                    case DEAD -> dead++;
                    case SKIPPED -> skipped++;
                }
            }
        }
        DispatchSummary summary = new DispatchSummary(claimed, expanded, sent, rescheduled, dead, skipped);
        if (!summary.isEmpty()) {
            LOGGER.info("Deliveries dispatched: {}", summary);
        }
        return summary;
    }

    private Outcome handle(Delivery delivery) {
        Optional<Notification> notification = relay.loadNotification(delivery);
        if (notification.isEmpty()) {
            relay.markSkipped(delivery, "notification no longer exists");
            return Outcome.SKIPPED;
        }
        return delivery.getScope() == DeliveryScope.AUDIENCE
                ? expand(delivery)
                : send(delivery, notification.get());
    }

    private Outcome expand(Delivery delivery) {
        Optional<Audience> audience = Audience.parse(delivery.getAudienceKey());
        if (audience.isEmpty()) {
            relay.markSkipped(delivery, "unknown audience " + delivery.getAudienceKey());
            return Outcome.SKIPPED;
        }
        try {
            List<Recipient> recipients = audienceResolver.resolve(audience.get());
            int inserted = relay.expand(delivery, recipients);
            LOGGER.debug("Audience {} of notification {} expanded into {} recipient(s)", delivery.getAudienceKey(),
                    delivery.getNotificationId(), inserted);
            return Outcome.EXPANDED;
        } catch (DirectoryUnavailableException unavailable) {
            return failure(delivery, unavailable);
        } catch (RuntimeException failure) {
            return failure(delivery, failure);
        }
    }

    private Outcome send(Delivery delivery, Notification notification) {
        DeliveryChannel channel = channels.get(delivery.getChannel());
        if (channel == null) {
            relay.markSkipped(delivery, "channel " + delivery.getChannel() + " is not enabled on this instance");
            return Outcome.SKIPPED;
        }
        if (DeliveryChannels.EMAIL.equals(delivery.getChannel())
                && relay.isOverHourlyLimit(delivery.getChannel(), delivery.getRecipient(), properties.email().maxPerHour())) {
            relay.markSkipped(delivery, "hourly limit of " + properties.email().maxPerHour() + " reached for " + delivery.getRecipient());
            return Outcome.SKIPPED;
        }
        try {
            channel.send(notification, new Recipient(delivery.getRecipientUsername(), delivery.getRecipient()));
            relay.markSent(delivery);
            return Outcome.SENT;
        } catch (Exception failure) {
            return failure(delivery, failure);
        }
    }

    private Outcome failure(Delivery delivery, Exception failure) {
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (delivery.getAttempts() >= delivery.getMaxAttempts()) {
            LOGGER.error("Delivery {} ({}, {}) gave up after {} attempts: {}", delivery.getId(), delivery.getChannel(),
                    delivery.getScope(), delivery.getAttempts(), error);
            relay.markDead(delivery, error);
            return Outcome.DEAD;
        }
        Instant next = retryPolicy.nextAttemptAt(delivery.getAttempts(), Instant.now());
        LOGGER.warn("Delivery {} ({}, {}) failed on attempt {}; next at {}: {}", delivery.getId(), delivery.getChannel(),
                delivery.getScope(), delivery.getAttempts(), next, error);
        relay.reschedule(delivery, next, error);
        return Outcome.RESCHEDULED;
    }

    private enum Outcome { EXPANDED, SENT, RESCHEDULED, DEAD, SKIPPED }
}
