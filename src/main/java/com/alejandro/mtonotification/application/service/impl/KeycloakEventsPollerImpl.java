package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.application.service.KeycloakEventsClient;
import com.alejandro.mtonotification.application.service.KeycloakEventsPoller;
import com.alejandro.mtonotification.application.service.SourceCursorService;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakProperties;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Fingerprints;
import com.alejandro.mtonotification.domain.model.Subject;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * El lector de Keycloak. Por fuente: arrendamiento, paginas de lo mas nuevo hacia atras hasta
 * cruzar {@code marca - solape} (o {@code ahora - initialLookback} la primera vez), y cada evento,
 * del mas antiguo al mas nuevo, por el inbox con su huella como clave. Un evento envenenado queda
 * FAILED en el inbox y no para la pasada; la marca avanza al final, al maximo {@code time} visto.
 * Ninguna llamada HTTP corre dentro de una transaccion.
 */
@Service
class KeycloakEventsPollerImpl implements KeycloakEventsPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakEventsPollerImpl.class);

    static final String SELF_SOURCE = "mto-notification";
    private static final int STALL_MULTIPLIER = 10;

    private final KeycloakEventsClient client;
    private final KeycloakLoginEventAdapter loginAdapter;
    private final KeycloakAdminEventAdapter adminAdapter;
    private final InboxMessageService inboxMessageService;
    private final ActivityIngestor activityIngestor;
    private final SourceCursorService cursorService;
    private final KeycloakProperties properties;
    private final Clock clock;
    /** Cuando empezo ESTA instancia a leer cada fuente: desde ahi se mide una caida que no vio empezar. */
    private final Map<SourceKind, Instant> readingSince = new ConcurrentHashMap<>();

    @Autowired
    KeycloakEventsPollerImpl(KeycloakEventsClient client, KeycloakLoginEventAdapter loginAdapter,
                             KeycloakAdminEventAdapter adminAdapter, InboxMessageService inboxMessageService,
                             ActivityIngestor activityIngestor, SourceCursorService cursorService,
                             KeycloakProperties properties) {
        this(client, loginAdapter, adminAdapter, inboxMessageService, activityIngestor, cursorService, properties,
                Clock.systemUTC());
    }

    KeycloakEventsPollerImpl(KeycloakEventsClient client, KeycloakLoginEventAdapter loginAdapter,
                             KeycloakAdminEventAdapter adminAdapter, InboxMessageService inboxMessageService,
                             ActivityIngestor activityIngestor, SourceCursorService cursorService,
                             KeycloakProperties properties, Clock clock) {
        this.client = client;
        this.loginAdapter = loginAdapter;
        this.adminAdapter = adminAdapter;
        this.inboxMessageService = inboxMessageService;
        this.activityIngestor = activityIngestor;
        this.cursorService = cursorService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Map<SourceKind, PollSummary> pollOnce() {
        Map<SourceKind, PollSummary> summaries = new EnumMap<>(SourceKind.class);
        if (properties.events().loginEnabled()) {
            summaries.put(SourceKind.KEYCLOAK_LOGIN, poll(SourceKind.KEYCLOAK_LOGIN));
        }
        if (properties.events().adminEnabled()) {
            summaries.put(SourceKind.KEYCLOAK_ADMIN, poll(SourceKind.KEYCLOAK_ADMIN));
        }
        return summaries;
    }

    @Override
    public PollSummary poll(SourceKind kind) {
        readingSince.putIfAbsent(kind, clock.instant());
        Optional<SourceCursorService.Lease> lease = cursorService.acquire(kind);
        if (lease.isEmpty()) {
            LOGGER.debug("Source {} is leased by another instance; skipping this poll", kind);
            return PollSummary.notLeased();
        }
        try {
            PollSummary summary = kind == SourceKind.KEYCLOAK_LOGIN ? pollLogin(lease.get()) : pollAdmin(lease.get());
            LOGGER.info("Polled {}: fetched={}, ingested={}, duplicates={}, failed={}", kind, summary.fetched(),
                    summary.ingested(), summary.duplicates(), summary.failed());
            return summary;
        } catch (RuntimeException failure) {
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            LOGGER.warn("Polling {} failed; the watermark stays where it was: {}", kind, error);
            cursorService.release(lease.get(), null, error);
            recordStallIfLong(kind, lease.get(), error);
            return new PollSummary(true, 0, 0, 0, 0, error);
        }
    }

    private PollSummary pollLogin(SourceCursorService.Lease lease) {
        List<Timed<KeycloakEvent>> events = fetch(lease, client::loginEvents, KeycloakEvent::time);
        return process(lease, events, event -> loginAdapter.toDraft(event),
                event -> command(KeycloakLoginEventAdapter.SOURCE_SERVICE, KeycloakLoginEventAdapter.fingerprint(event),
                        event.type(), "user", event.userId(), event.raw()));
    }

    private PollSummary pollAdmin(SourceCursorService.Lease lease) {
        List<Timed<KeycloakAdminEvent>> events = fetch(lease, client::adminEvents, KeycloakAdminEvent::time);
        return process(lease, events, event -> adminAdapter.toDraft(event),
                event -> command(KeycloakAdminEventAdapter.SOURCE_SERVICE, KeycloakAdminEventAdapter.fingerprint(event),
                        event.operationType(), event.resourceType(), event.resourcePath(), event.raw()));
    }

    /**
     * Pagina de lo mas nuevo hacia atras hasta que una pagina trae algo anterior a {@code since} o
     * se agota el tope de paginas. Lo que llega entre pagina y pagina puede desplazar la siguiente:
     * un repetido lo para el inbox y un hueco lo cubre el solape de la siguiente pasada.
     */
    private <T> List<Timed<T>> fetch(SourceCursorService.Lease lease, PageFetcher<T> fetcher, Function<T, Long> time) {
        KeycloakProperties.Events events = properties.events();
        Instant since = lease.lastEventTime() == null
                ? clock.instant().minus(events.initialLookback())
                : lease.lastEventTime().minus(events.overlap());
        List<Timed<T>> collected = new ArrayList<>();
        int pageSize = events.pageSize();
        for (int page = 0; page < events.maxPagesPerPoll(); page++) {
            List<T> items = fetcher.fetch(page * pageSize, pageSize);
            boolean crossed = false;
            for (T item : items) {
                Long millis = time.apply(item);
                Instant at = millis == null ? clock.instant() : Instant.ofEpochMilli(millis);
                if (at.isBefore(since)) {
                    crossed = true;
                    break;
                }
                collected.add(new Timed<>(item, at));
            }
            if (crossed || items.size() < pageSize) {
                break;
            }
        }
        return collected;
    }

    private <T> PollSummary process(SourceCursorService.Lease lease, List<Timed<T>> events,
                                    Function<T, Optional<ActivityEventDraft>> adapter, Function<T, InboxMessageCommand> commands) {
        int ingested = 0;
        int duplicates = 0;
        int failed = 0;
        Instant newest = lease.lastEventTime();
        // Del mas antiguo al mas nuevo: una racha se detecta en el orden en que paso.
        for (int index = events.size() - 1; index >= 0; index--) {
            Timed<T> timed = events.get(index);
            if (newest == null || timed.at().isAfter(newest)) {
                newest = timed.at();
            }
            Optional<ActivityEventDraft> draft = adapter.apply(timed.item());
            if (draft.isEmpty()) {
                continue;
            }
            InboxMessageCommand command = commands.apply(timed.item());
            try {
                InboxProcessingResult result = inboxMessageService.process(command, () -> activityIngestor.ingest(draft.get()));
                if (result == InboxProcessingResult.PROCESSED) {
                    ingested++;
                } else {
                    duplicates++;
                }
            } catch (RuntimeException failure) {
                failed++;
                LOGGER.error("Keycloak event could not be ingested and stays FAILED in the inbox: source={}, messageId={}",
                        command.sourceService(), command.messageId(), failure);
                inboxMessageService.recordFailure(command, failure);
            }
        }
        cursorService.release(lease, newest, null);
        return new PollSummary(true, events.size(), ingested, duplicates, failed, null);
    }

    private static InboxMessageCommand command(String sourceService, String fingerprint, String eventType,
                                               String aggregateType, String aggregateId, String raw) {
        String payload = raw == null || raw.isBlank() ? "{}" : raw;
        return new InboxMessageCommand(fingerprint, sourceService, truncate(eventType, 150), truncate(aggregateType, 150),
                truncate(aggregateId, 100), null, null, null, Fingerprints.sha256(payload), payload, null);
    }

    /**
     * Una fuente que lleva demasiado sin leerse bien deja una linea SYSTEM; la regla avisa a explotacion.
     * «Demasiado» se cuenta desde su ultima pasada buena o desde que esta instancia empezo a leerla, lo
     * que sea mas reciente: una fuente que nunca ha leido bien (el primer arranque, antes de que exista la
     * cuenta de servicio) o cuyo ultimo exito es de antes de un reinicio no se da por parada al primer
     * fallo, sino cuando esta instancia lleva el umbral entero sin conseguirlo.
     */
    private void recordStallIfLong(SourceKind kind, SourceCursorService.Lease lease, String error) {
        Duration threshold = properties.events().pollInterval().multipliedBy(STALL_MULTIPLIER);
        Instant now = clock.instant();
        Instant lastSuccess = lease.lastSuccessAt();
        Instant failingSince = readingSince.getOrDefault(kind, now);
        if (lastSuccess != null && lastSuccess.isAfter(failingSince)) {
            failingSince = lastSuccess;
        }
        if (failingSince.isAfter(now.minus(threshold))) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", kind.name());
        payload.put("lastSuccessAt", lastSuccess == null ? "never" : lastSuccess.toString());
        payload.put("error", error);
        long hourBucket = now.getEpochSecond() / 3600;
        try {
            activityIngestor.ingest(ActivityEventDraft.builder()
                    .source(SELF_SOURCE, "stalled:" + kind.name() + ":" + hourBucket)
                    .type(ActivityTypes.SYSTEM_SOURCE_STALLED)
                    .severity(ActivitySeverity.WARNING)
                    .occurredAt(now)
                    .subject(Subject.of("source", kind.name()))
                    .payload(payload)
                    .build());
        } catch (RuntimeException ignored) {
            LOGGER.warn("Could not record the stalled source {}", kind);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record Timed<T>(T item, Instant at) {
    }

    @FunctionalInterface
    private interface PageFetcher<T> {
        List<T> fetch(int first, int max);
    }
}
