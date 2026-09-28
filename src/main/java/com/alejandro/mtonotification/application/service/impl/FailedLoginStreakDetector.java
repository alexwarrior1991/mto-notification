package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.DerivedEventDetector;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.Subject;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tres fallos de acceso del mismo usuario, o de la misma IP, en diez minutos: UN evento derivado
 * {@code access.login.streak}. Corre tras ingerir cada fallo, en su transaccion, con un recuento
 * sobre el registro (los duplicados no inflan: el inbox y la unicidad del registro ya los pararon).
 *
 * <p>El derivado entra por el mismo ingestor con {@code source_event_id = streak:<dimension>:<valor>:<primer fallo>}:
 * el cuarto y el quinto fallo dentro de la misma ventana calculan la misma clave y no insertan nada,
 * y dos instancias no disparan dos veces. Es lo que hace que la racha sea un aviso y no tres.</p>
 */
@Service
class FailedLoginStreakDetector implements DerivedEventDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(FailedLoginStreakDetector.class);

    static final String SELF_SOURCE = "mto-notification";
    static final String DIMENSION_USERNAME = "username";
    static final String DIMENSION_IP = "ip";

    private final ActivityEventRepository activityEventRepository;
    private final ActivityIngestor activityIngestor;
    private final NotificationProperties properties;

    FailedLoginStreakDetector(ActivityEventRepository activityEventRepository,
                              @Lazy ActivityIngestor activityIngestor,
                              NotificationProperties properties) {
        this.activityEventRepository = activityEventRepository;
        this.activityIngestor = activityIngestor;
        this.properties = properties;
    }

    @Override
    public void afterIngested(ActivityEvent event, ActivityEventDraft draft) {
        if (!ActivityTypes.ACCESS_LOGIN_FAILED.equals(event.getType())) {
            return;
        }
        NotificationProperties.Streak streak = properties.access().streak();
        Instant to = event.getOccurredAt();
        Instant from = to.minus(streak.window());

        if (event.getActorUsername() != null) {
            long count = activityEventRepository.countByTypeAndActorInWindow(ActivityTypes.ACCESS_LOGIN_FAILED, event.getActorUsername(), from, to);
            if (count >= streak.threshold()) {
                Instant earliest = activityEventRepository.earliestByTypeAndActorInWindow(ActivityTypes.ACCESS_LOGIN_FAILED, event.getActorUsername(), from, to);
                ingestStreak(event, DIMENSION_USERNAME, event.getActorUsername(), count, earliest == null ? from : earliest, to, null);
            }
        }
        InetAddress ip = event.getIpAddress();
        if (ip != null) {
            String address = ip.getHostAddress();
            long count = activityEventRepository.countByTypeAndIpInWindow(ActivityTypes.ACCESS_LOGIN_FAILED, address, from, to);
            if (count >= streak.threshold()) {
                Instant earliest = activityEventRepository.earliestByTypeAndIpInWindow(ActivityTypes.ACCESS_LOGIN_FAILED, address, from, to);
                ingestStreak(event, DIMENSION_IP, address, count, earliest == null ? from : earliest, to, address);
            }
        }
    }

    private void ingestStreak(ActivityEvent trigger, String dimension, String value, long count,
                              Instant windowStart, Instant windowEnd, String ipAddress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dimension", dimension);
        payload.put("value", value);
        payload.put("count", count);
        payload.put("windowStart", windowStart.toString());
        payload.put("windowEnd", windowEnd.toString());
        payload.put("lastFailureEventId", trigger.getId().toString());

        Actor actor = DIMENSION_USERNAME.equals(dimension)
                ? Actor.person(value, trigger.getActorId())
                : Actor.system();

        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(SELF_SOURCE, "streak:" + dimension + ":" + value + ":" + windowStart.toEpochMilli())
                .type(ActivityTypes.ACCESS_LOGIN_STREAK)
                .severity(ActivitySeverity.CRITICAL)
                .occurredAt(windowEnd)
                .actor(actor)
                .subject(DIMENSION_USERNAME.equals(dimension) ? Subject.of("user", trigger.getActorId(), value) : Subject.of("ip", value, value))
                .correlationId(trigger.getCorrelationId())
                .ipAddress(ipAddress != null ? ipAddress : (trigger.getIpAddress() == null ? null : trigger.getIpAddress().getHostAddress()))
                .payload(payload)
                .build())
                .ifPresent(streak -> LOGGER.warn("Failed login streak detected: {}={} ({} failures between {} and {})",
                        dimension, value, count, windowStart, windowEnd));
    }
}
