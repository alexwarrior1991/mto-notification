package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.application.dto.messaging.FailedInboxMessage;
import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * El lector de Keycloak. La Admin API (26.1) solo sabe dar los eventos del mas nuevo al mas antiguo,
 * paginados con {@code first}/{@code max}; su filtro de fecha es por dia y no hay orden ascendente.
 * Por fuente, con su arrendamiento:
 *
 * <ol>
 *   <li>Los FAILED del inbox a los que les toca otro intento se vuelven a aplicar desde su JSON
 *       guardado, con un tope de intentos y una espera que se dobla.</li>
 *   <li>Se lee desde la cabeza hacia atras hasta cruzar {@code marca - solape} (o
 *       {@code ahora - initialLookback} la primera vez). Si cabe en un tramo, se procesa del mas
 *       antiguo al mas nuevo y la marca queda en el mas nuevo.</li>
 *   <li>Si no cabe (una rafaga, una importacion, el lector parado un rato), nada de lo leido se
 *       procesa: se busca a que profundidad esta la marca y se lee el tramo que queda justo encima de
 *       ella, el mas antiguo, hacia atras hasta cruzarla. La marca avanza al mas nuevo de ese tramo
 *       y se repite hasta alcanzar la cabeza o agotar el tiempo de la pasada; la siguiente sigue
 *       desde la marca. Ningun evento se salta: leer hacia atras con eventos nuevos entrando por la
 *       cabeza solo repite, y lo repetido lo para el inbox.</li>
 * </ol>
 *
 * <p>Un evento que falla (el adaptador o la ingesta) queda FAILED en el inbox con su motivo y no
 * para a los demas: la marca pasa, porque el evento ya esta guardado y lo lleva el reintento. Si ni
 * eso se puede apuntar (la base caida), o Keycloak no responde, la pasada para ahi y la marca se
 * queda en lo ultimo procesado. Ninguna llamada HTTP corre dentro de una transaccion.</p>
 */
@Service
class KeycloakEventsPollerImpl implements KeycloakEventsPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakEventsPollerImpl.class);

    static final String SELF_SOURCE = "mto-notification";
    /** {@code mto_notification_keycloak_events_total{source, result}}: ingested, failed, terminal, recovered. */
    static final String EVENTS_METER = "mto.notification.keycloak.events";
    private static final int STALL_MULTIPLIER = 10;
    /** Cuantas veces se ensancha la relectura de una frontera de pagina que parte un mismo milisegundo. */
    private static final int MAX_TIE_WIDENINGS = 5;

    private final KeycloakEventsClient client;
    private final KeycloakLoginEventAdapter loginAdapter;
    private final KeycloakAdminEventAdapter adminAdapter;
    private final InboxMessageService inboxMessageService;
    private final ActivityIngestor activityIngestor;
    private final SourceCursorService cursorService;
    private final KeycloakProperties properties;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    /** Cuando empezo ESTA instancia a leer cada fuente: desde ahi se mide una caida que no vio empezar. */
    private final Map<SourceKind, Instant> readingSince = new ConcurrentHashMap<>();

    @Autowired
    KeycloakEventsPollerImpl(KeycloakEventsClient client, KeycloakLoginEventAdapter loginAdapter,
                             KeycloakAdminEventAdapter adminAdapter, InboxMessageService inboxMessageService,
                             ActivityIngestor activityIngestor, SourceCursorService cursorService,
                             KeycloakProperties properties, JsonMapper jsonMapper, MeterRegistry meterRegistry) {
        this(client, loginAdapter, adminAdapter, inboxMessageService, activityIngestor, cursorService, properties,
                jsonMapper, meterRegistry, Clock.systemUTC());
    }

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
        this(client, loginAdapter, adminAdapter, inboxMessageService, activityIngestor, cursorService, properties,
                JsonMapper.builder().build(), new SimpleMeterRegistry(), clock);
    }

    KeycloakEventsPollerImpl(KeycloakEventsClient client, KeycloakLoginEventAdapter loginAdapter,
                             KeycloakAdminEventAdapter adminAdapter, InboxMessageService inboxMessageService,
                             ActivityIngestor activityIngestor, SourceCursorService cursorService,
                             KeycloakProperties properties, JsonMapper jsonMapper, MeterRegistry meterRegistry, Clock clock) {
        this.client = client;
        this.loginAdapter = loginAdapter;
        this.adminAdapter = adminAdapter;
        this.inboxMessageService = inboxMessageService;
        this.activityIngestor = activityIngestor;
        this.cursorService = cursorService;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
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
        Optional<SourceCursorService.Lease> acquired = cursorService.acquire(kind);
        if (acquired.isEmpty()) {
            LOGGER.debug("Source {} is leased by another instance; skipping this poll", kind);
            return PollSummary.notLeased();
        }
        SourceCursorService.Lease lease = acquired.get();
        Progress progress = new Progress(lease.lastEventTime());
        try {
            if (kind == SourceKind.KEYCLOAK_LOGIN) {
                read(loginSource(), lease, progress);
            } else {
                read(adminSource(), lease, progress);
            }
            cursorService.release(lease, progress.watermark, null);
            PollSummary summary = progress.summary(null);
            LOGGER.info("Polled {}: fetched={}, ingested={}, duplicates={}, failed={}, recovered={}, caughtUp={}", kind,
                    summary.fetched(), summary.ingested(), summary.duplicates(), summary.failed(), summary.recovered(),
                    progress.caughtUp);
            return summary;
        } catch (LeaseLostException lost) {
            // Otra instancia tiene ya la fuente y sigue desde la marca: aqui no se suelta ni se apunta nada.
            LOGGER.warn("The lease of {} expired in the middle of the pass and another instance took it; this pass stops", kind);
            return progress.summary("lease lost");
        } catch (RuntimeException failure) {
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            LOGGER.warn("Polling {} failed; the watermark stays at the last event processed: {}", kind, error);
            try {
                cursorService.release(lease, progress.advanced ? progress.watermark : null, error);
            } catch (RuntimeException unreleased) {
                LOGGER.warn("Could not release the lease of {}; it expires by itself: {}", kind, unreleased.getMessage());
            }
            recordStallIfLong(kind, lease, error);
            return progress.summary(error);
        }
    }

    private Source<KeycloakEvent> loginSource() {
        return new Source<>(KeycloakLoginEventAdapter.SOURCE_SERVICE, client::loginEvents, KeycloakEvent::time,
                loginAdapter::toDraft,
                event -> command(KeycloakLoginEventAdapter.SOURCE_SERVICE, KeycloakLoginEventAdapter.fingerprint(event),
                        event.type(), "user", event.userId(), event.raw()),
                raw -> jsonMapper.readValue(raw, KeycloakEvent.class).withRaw(raw));
    }

    private Source<KeycloakAdminEvent> adminSource() {
        return new Source<>(KeycloakAdminEventAdapter.SOURCE_SERVICE, client::adminEvents, KeycloakAdminEvent::time,
                adminAdapter::toDraft,
                event -> command(KeycloakAdminEventAdapter.SOURCE_SERVICE, KeycloakAdminEventAdapter.fingerprint(event),
                        event.operationType(), event.resourceType(), event.resourcePath(), event.raw()),
                raw -> jsonMapper.readValue(raw, KeycloakAdminEvent.class).withRaw(raw));
    }

    // ------------------------------------------------------------------------------------------
    // La pasada
    // ------------------------------------------------------------------------------------------

    private <T> void read(Source<T> source, SourceCursorService.Lease lease, Progress progress) {
        KeycloakProperties.Events events = properties.events();
        Instant deadline = clock.instant().plus(events.maxPassDuration());
        retryFailed(source, progress);

        // floor: hasta donde se da por leido; since: desde donde se relee, por lo que Keycloak hace visible tarde.
        Instant floor = lease.lastEventTime() == null ? clock.instant().minus(events.initialLookback()) : lease.lastEventTime();
        Instant since = lease.lastEventTime() == null ? floor : floor.minus(events.overlap());
        int chunkSize = events.chunkSize();

        Walk<T> walk = walk(source, 0, since, chunkSize);
        int start = 0;
        while (true) {
            if (!walk.reachedSince()) {
                // No cabe en un tramo: lo leido es lo mas nuevo y procesarlo seria saltarse lo de debajo.
                int depth = locate(source, floor);
                start = Math.max(0, depth - chunkSize);
                LOGGER.info("Backlog in {}: about {} events above the watermark {}; reading the oldest from offset {}",
                        source.sourceService(), depth, floor, start);
                walk = walk(source, start, since, Integer.MAX_VALUE);
            }
            boolean finished = process(source, lease, walk.oldestFirst(), progress, deadline);
            if (finished && start == 0) {
                progress.caughtUp = true;
                return;
            }
            if (!finished || clock.instant().isAfter(deadline)) {
                return;
            }
            // Queda atraso por encima de este tramo: la marca sube a su evento mas nuevo y se sigue desde ahi.
            Instant newest = walk.newest();
            if (newest == null || !newest.isAfter(floor)) {
                // Un tramo entero en el mismo milisegundo que la marca (o nada nuevo): se ensancha para avanzar.
                chunkSize = chunkSize > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : chunkSize * 2;
            } else {
                floor = newest;
                since = floor.minus(events.overlap());
            }
            if (!cursorService.advance(lease, progress.watermark)) {
                throw new LeaseLostException();
            }
            walk = Walk.incomplete();
        }
    }

    /**
     * Lee hacia atras desde {@code start} hasta cruzar {@code since} o el final de la lista. Con
     * {@code limit}, para al pasarlo sin haber cruzado. Lo que entra por la cabeza entre pagina y
     * pagina empuja la lista hacia atras: se repiten eventos (se quitan por su huella), nunca se salta.
     */
    private <T> Walk<T> walk(Source<T> source, int start, Instant since, int limit) {
        int pageSize = properties.events().pageSize();
        List<Timed<T>> collected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int offset = start;
        while (true) {
            List<T> items = source.fetcher().fetch(offset, pageSize);
            if (!items.isEmpty() && !collected.isEmpty()) {
                Instant boundary = timeOf(source, items.getFirst());
                if (boundary.equals(collected.getLast().at())) {
                    readWholeMillisecond(source, offset, boundary, collected, seen);
                }
            }
            boolean crossed = false;
            for (T item : items) {
                Instant at = timeOf(source, item);
                if (at.isBefore(since)) {
                    crossed = true;
                    break;
                }
                collect(source, item, at, collected, seen);
            }
            if (crossed || items.size() < pageSize) {
                return new Walk<>(collected, true);
            }
            if (collected.size() >= limit) {
                return new Walk<>(collected, false);
            }
            offset += pageSize;
        }
    }

    /**
     * Keycloak ordena solo por {@code time}: dos eventos del mismo milisegundo no tienen orden entre
     * si, y la base puede darlos en otro orden en la consulta de la pagina siguiente, con lo que uno
     * no saldria en ninguna de las dos. Cuando la frontera entre dos paginas cae dentro de un mismo
     * milisegundo, ese milisegundo se vuelve a pedir entero en UNA respuesta que lo contenga de
     * principio a fin, ensanchandola hasta que lo haga.
     */
    private <T> void readWholeMillisecond(Source<T> source, int boundaryOffset, Instant millisecond,
                                          List<Timed<T>> collected, Set<String> seen) {
        int span = properties.events().pageSize();
        for (int widening = 0; widening < MAX_TIE_WIDENINGS; widening++) {
            int from = Math.max(0, boundaryOffset - span);
            int max = boundaryOffset - from + span;
            List<T> items = source.fetcher().fetch(from, max);
            for (T item : items) {
                Instant at = timeOf(source, item);
                if (at.equals(millisecond)) {
                    collect(source, item, at, collected, seen);
                }
            }
            boolean startsAbove = from == 0 || (!items.isEmpty() && timeOf(source, items.getFirst()).isAfter(millisecond));
            boolean endsBelow = items.size() < max || timeOf(source, items.getLast()).isBefore(millisecond);
            if (startsAbove && endsBelow) {
                return;
            }
            span = span > Integer.MAX_VALUE / 4 ? span : span * 2;
        }
        LOGGER.warn("More events of {} share the millisecond {} than fit in one answer of {}; some may be read on a later pass",
                source.sourceService(), millisecond, span);
    }

    private <T> void collect(Source<T> source, T item, Instant at, List<Timed<T>> collected, Set<String> seen) {
        InboxMessageCommand command;
        try {
            command = source.command().apply(item);
        } catch (RuntimeException unreadable) {
            // Sin huella no hay clave con la que apuntarlo en el inbox: se cuenta y se sigue.
            LOGGER.error("Keycloak event of {} has no usable fingerprint and is skipped", source.sourceService(), unreadable);
            collected.add(new Timed<>(item, at, null));
            return;
        }
        if (seen.add(command.messageId())) {
            collected.add(new Timed<>(item, at, command));
        }
    }

    /**
     * A que profundidad esta la marca: cuantos eventos, desde la cabeza, son de {@code floor} o
     * posteriores. La lista va de mas nuevo a mas antiguo, asi que se busca por biseccion pidiendo un
     * evento cada vez. Si entran eventos mientras tanto el resultado se queda corto, y solo cuesta
     * repetir: el tramo se lee hacia atras hasta cruzar la marca, este donde este.
     */
    private <T> int locate(Source<T> source, Instant floor) {
        int low = 0;
        int high = Math.max(1, properties.events().pageSize());
        while (!olderThan(source, high, floor)) {
            low = high + 1;
            if (high > Integer.MAX_VALUE / 2) {
                return high;
            }
            high *= 2;
        }
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (olderThan(source, middle, floor)) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private <T> boolean olderThan(Source<T> source, int offset, Instant floor) {
        List<T> one = source.fetcher().fetch(offset, 1);
        return one.isEmpty() || timeOf(source, one.getFirst()).isBefore(floor);
    }

    private <T> Instant timeOf(Source<T> source, T item) {
        Long millis = source.time().apply(item);
        return millis == null ? clock.instant() : Instant.ofEpochMilli(millis);
    }

    /**
     * Del mas antiguo al mas nuevo (una racha se detecta en el orden en que paso), y la marca sube con
     * cada evento atendido: es el prefijo ya procesado, y lo que se relee incluye su mismo milisegundo.
     *
     * @return {@code false} si se acabo el tiempo de la pasada antes que el tramo
     */
    private <T> boolean process(Source<T> source, SourceCursorService.Lease lease, List<Timed<T>> events,
                                Progress progress, Instant deadline) {
        Set<String> alreadyFailed = events.isEmpty() ? Set.of() : inboxMessageService.failedAmong(source.sourceService(),
                events.stream().map(Timed::command).filter(Objects::nonNull).map(InboxMessageCommand::messageId).toList());
        Duration renewEvery = properties.events().leaseTtl().dividedBy(3);
        Instant renewAt = clock.instant().plus(renewEvery);
        boolean any = false;
        for (Timed<T> timed : events) {
            Instant now = clock.instant();
            if (any && now.isAfter(deadline)) {
                return false;
            }
            if (now.isAfter(renewAt)) {
                if (!cursorService.advance(lease, progress.watermark)) {
                    throw new LeaseLostException();
                }
                renewAt = now.plus(renewEvery);
            }
            handle(source, timed, alreadyFailed, progress);
            progress.reach(timed.at());
            any = true;
        }
        return true;
    }

    private <T> void handle(Source<T> source, Timed<T> timed, Set<String> alreadyFailed, Progress progress) {
        progress.fetched++;
        InboxMessageCommand command = timed.command();
        if (command == null) {
            progress.failed++;
            return;
        }
        if (alreadyFailed.contains(command.messageId())) {
            // Ya fallo y esta en el inbox: lo lleva el reintento con su espera y su tope, no cada relectura.
            return;
        }
        Optional<ActivityEventDraft> draft;
        try {
            draft = source.adapter().apply(timed.item());
        } catch (DirectoryUnavailableException keycloakDown) {
            throw keycloakDown;
        } catch (RuntimeException poisoned) {
            fail(source, command, poisoned, 1, progress);
            return;
        }
        if (draft.isEmpty()) {
            return;
        }
        try {
            InboxProcessingResult result = inboxMessageService.process(command, () -> activityIngestor.ingest(draft.get()));
            if (result == InboxProcessingResult.PROCESSED) {
                progress.ingested++;
                count(source, "ingested");
            } else {
                progress.duplicates++;
            }
        } catch (RuntimeException failure) {
            fail(source, command, failure, 1, progress);
        }
    }

    /** Los FAILED a los que les toca: se vuelven a aplicar desde el JSON que guardo el inbox, sin pedirlos a Keycloak. */
    private <T> void retryFailed(Source<T> source, Progress progress) {
        KeycloakProperties.Retry retry = properties.events().retry();
        List<FailedInboxMessage> due = inboxMessageService.failedDueForRetry(source.sourceService(), retry.maxAttempts(),
                retry.backoff(), retry.maxBackoff(), retry.batchSize());
        for (FailedInboxMessage failed : due) {
            InboxMessageCommand command = new InboxMessageCommand(failed.messageId(), source.sourceService(), failed.eventType(),
                    failed.aggregateType(), failed.aggregateId(), null, null, null, failed.payloadHash(), failed.payload(), null);
            try {
                Optional<ActivityEventDraft> draft = source.adapter().apply(source.parser().apply(failed.payload()));
                inboxMessageService.process(command, () -> draft.ifPresent(activityIngestor::ingest));
                progress.recovered++;
                count(source, "recovered");
                LOGGER.info("Keycloak event recovered on attempt {}: source={}, messageId={}", failed.attempts() + 1,
                        source.sourceService(), failed.messageId());
            } catch (DirectoryUnavailableException keycloakDown) {
                throw keycloakDown;
            } catch (RuntimeException failure) {
                fail(source, command, failure, failed.attempts() + 1, progress);
            }
        }
    }

    /**
     * Deja el evento FAILED en el inbox con su motivo. Si ni eso se puede (la base no responde), la
     * excepcion sale y para la pasada con la marca antes de este evento: no se pierde.
     */
    private <T> void fail(Source<T> source, InboxMessageCommand command, RuntimeException failure, int attempt, Progress progress) {
        boolean terminal = attempt >= properties.events().retry().maxAttempts();
        inboxMessageService.recordFailure(command, failure);
        progress.failed++;
        count(source, terminal ? "terminal" : "failed");
        if (terminal) {
            LOGGER.error("Keycloak event failed {} times and will not be retried; it stays FAILED in the inbox: source={}, messageId={}",
                    attempt, command.sourceService(), command.messageId(), failure);
        } else {
            LOGGER.warn("Keycloak event could not be ingested (attempt {}); it stays FAILED in the inbox and will be retried: source={}, messageId={}",
                    attempt, command.sourceService(), command.messageId(), failure);
        }
    }

    private void count(Source<?> source, String result) {
        meterRegistry.counter(EVENTS_METER, "source", source.sourceService(), "result", result).increment();
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

    /** Una fuente: como se piden sus paginas, como se lee un evento y como se vuelve a leer desde el inbox. */
    private record Source<T>(String sourceService, PageFetcher<T> fetcher, Function<T, Long> time,
                             Function<T, Optional<ActivityEventDraft>> adapter, Function<T, InboxMessageCommand> command,
                             Function<String, T> parser) {
    }

    /** @param command la clave del inbox, o {@code null} si el evento no deja ni calcular su huella */
    private record Timed<T>(T item, Instant at, InboxMessageCommand command) {
    }

    /** Lo leido en una bajada: tal como llego (del mas nuevo al mas antiguo) y si llego hasta la marca. */
    private record Walk<T>(List<Timed<T>> events, boolean reachedSince) {

        static <T> Walk<T> incomplete() {
            return new Walk<>(List.of(), false);
        }

        List<Timed<T>> oldestFirst() {
            List<Timed<T>> ordered = new ArrayList<>(events);
            Collections.reverse(ordered);
            ordered.sort(Comparator.comparing(Timed::at));
            return ordered;
        }

        Instant newest() {
            return events.stream().map(Timed::at).max(Comparator.naturalOrder()).orElse(null);
        }
    }

    /** Lo que lleva hecha una pasada; la marca solo sube sobre lo ya atendido. */
    private static final class Progress {
        private Instant watermark;
        private boolean advanced;
        private boolean caughtUp;
        private int fetched;
        private int ingested;
        private int duplicates;
        private int failed;
        private int recovered;

        Progress(Instant watermark) {
            this.watermark = watermark;
        }

        void reach(Instant at) {
            if (watermark == null || at.isAfter(watermark)) {
                watermark = at;
            }
            advanced = true;
        }

        PollSummary summary(String error) {
            return new PollSummary(true, fetched, ingested, duplicates, failed, recovered, error);
        }
    }

    /** El arrendamiento caduco a mitad de la pasada y otra instancia lo tomo. */
    private static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("lease lost", null, false, false);
        }
    }

    @FunctionalInterface
    private interface PageFetcher<T> {
        List<T> fetch(int first, int max);
    }
}
