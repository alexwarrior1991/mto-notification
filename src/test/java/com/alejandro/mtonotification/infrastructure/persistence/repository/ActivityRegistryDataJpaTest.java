package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.BurstAggregator;
import com.alejandro.mtonotification.application.service.RetentionPurge;
import com.alejandro.mtonotification.application.service.RuleEngine;
import com.alejandro.mtonotification.application.service.SourceCursorService;
import com.alejandro.mtonotification.application.service.ThrottleGate;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakProperties;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.ActorKind;
import com.alejandro.mtonotification.domain.model.Subject;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityBurst;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityBurstStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceCursor;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.persistence.specification.ActivityEventSpecification;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * El registro contra PostgreSQL: la idempotencia por {@code (source_service, source_event_id)}, los
 * CHECK del esquema, la racha derivada, la fusion del evento de Keycloak con el de mto-users, las
 * rafagas con su cierre en carrera, los frenos por regla, el arrendamiento del lector y la purga.
 * Todo lo que decide la base y no el codigo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, JacksonAutoConfiguration.class})
@TestPropertySource(properties = {
        "app.notification.rules-location=classpath:notification-rules.yml",
        "app.notification.access.streak.threshold=3",
        "app.notification.access.streak.window=PT10M",
        "app.notification.burst.idle-timeout=PT0S",
        "app.notification.burst.sample-size=2",
        "app.keycloak.base-url=http://keycloak:8080",
        "app.keycloak.realm=mto",
        "app.keycloak.client-registration-id=mto-services",
        "app.keycloak.events.lease-ttl=PT2M"
})
class ActivityRegistryDataJpaTest extends PostgreSQLTestContainer {

    /** Los servicios son package-private tras sus interfaces: se recogen por nombre, con sus properties. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({NotificationProperties.class, KeycloakProperties.class})
    @ComponentScan(basePackages = "com.alejandro.mtonotification.application.service.impl", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                    ".*\\.JsonPayloads", ".*\\.ActivityIngestorImpl", ".*\\.FailedLoginStreakDetector", ".*\\.UsersChangeCorrelator",
                    ".*\\.BurstAggregatorImpl", ".*\\.ThrottleGateImpl", ".*\\.RetentionPurgeImpl", ".*\\.SourceCursorServiceImpl"}))
    static class RegistryServicesConfiguration {
    }

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private ActivityBurstRepository activityBurstRepository;

    @Autowired
    private RuleThrottleRepository ruleThrottleRepository;

    @Autowired
    private SourceCursorRepository sourceCursorRepository;

    @Autowired
    private ActivityIngestor activityIngestor;

    @Autowired
    private BurstAggregator burstAggregator;

    @Autowired
    private ThrottleGate throttleGate;

    @Autowired
    private SourceCursorService sourceCursorService;

    @Autowired
    private RetentionPurge retentionPurge;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private RuleEngine ruleEngine;

    // --- el registro ---

    @Test
    void anEventIsRecordedOnceWhateverTheNumberOfDeliveries() {
        ActivityEventDraft draft = failedLogin("alice", "10.0.0.1", "fp-1", Instant.now());

        Optional<ActivityEvent> first = activityIngestor.ingest(draft);
        Optional<ActivityEvent> again = activityIngestor.ingest(draft);

        assertTrue(first.isPresent());
        assertTrue(again.isEmpty());
        ActivityEvent stored = reload(first.get().getId());
        assertNotNull(stored.getSeq());
        assertEquals(ActivityCategory.ACCESS, stored.getCategory());
        assertEquals("10.0.0.1", stored.getIpAddress().getHostAddress());
        assertTrue(stored.getPayload().contains("\"username\": \"alice\"") && stored.getPayload().contains("\"error\": \"invalid_user_credentials\""),
                "jsonb reordena las claves: " + stored.getPayload());
        assertNotNull(stored.getRecordedAt());
        verify(ruleEngine, times(1)).evaluate(any(), any());
    }

    @Test
    void theSchemaKeepsTheIpOutOfEverythingButAccessAndRejectsAMalformedType() {
        assertThrows(DataAccessException.class, () -> activityEventRepository.insertIfMissing(
                "s", "ip-outside-access", "USERS", ActivityTypes.USERS_ADMIN_USER_UPDATED, "INFO", Instant.now(),
                "PERSON", "alice", null, null, null, null, null, "10.0.0.1", 1, "{}"));
        entityManager.clear();
        assertThrows(DataAccessException.class, () -> activityEventRepository.insertIfMissing(
                "s", "bad-type", "USERS", "Users.Admin", "INFO", Instant.now(),
                "PERSON", "alice", null, null, null, null, null, null, 1, "{}"));
    }

    @Test
    void theSpecificationsFilterByCategoryActorSubjectIpAndWindow() {
        Instant now = Instant.now();
        activityIngestor.ingest(failedLogin("alice", "10.0.0.1", "a1", now.minusSeconds(60)));
        activityIngestor.ingest(failedLogin("bob", "10.0.0.2", "b1", now.minusSeconds(30)));
        activityIngestor.ingest(ActivityEventDraft.builder().source("mto-configuration", "c1").type("configuration.track.deleted")
                .occurredAt(now).actor(Actor.person("carol", "u3")).subject(Subject.of("track", "7", "Via 7")).build());

        assertEquals(2, activityEventRepository.count(ActivityEventSpecification.categoryEquals(ActivityCategory.ACCESS)));
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.categoryNot(ActivityCategory.ACCESS)));
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.actorUsernameEquals("bob")));
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.subjectTypeEquals("track")
                .and(ActivityEventSpecification.subjectIdEquals("7"))));
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.ipAddressEquals(ip("10.0.0.2"))));
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.occurredBetween(now.minusSeconds(45), now.minusSeconds(15))));
        assertEquals(3, activityEventRepository.count(ActivityEventSpecification.notSuperseded()));
        assertEquals(2, activityEventRepository.count(ActivityEventSpecification.typeIn(List.of(ActivityTypes.ACCESS_LOGIN_FAILED))));
    }

    // --- rachas ---

    @Test
    void threeFailuresOfOneUserDeriveOneStreakAndTheFourthDoesNotRepeatIt() {
        Instant now = Instant.now();
        activityIngestor.ingest(failedLogin("alice", "10.0.0.1", "f1", now.minusSeconds(120)));
        activityIngestor.ingest(failedLogin("alice", "10.0.0.7", "f2", now.minusSeconds(60)));
        assertEquals(0, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK)));

        activityIngestor.ingest(failedLogin("alice", "10.0.0.9", "f3", now));
        List<ActivityEvent> streaks = activityEventRepository.findAll(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK));
        assertEquals(1, streaks.size());
        ActivityEvent streak = streaks.getFirst();
        assertEquals("mto-notification", streak.getSourceService());
        assertEquals("streak:username:alice:" + now.minusSeconds(120).toEpochMilli(), streak.getSourceEventId());
        assertEquals(ActivitySeverity.CRITICAL, streak.getSeverity());
        assertEquals("alice", streak.getActorUsername());
        assertTrue(streak.getPayload().contains("\"count\": 3") || streak.getPayload().contains("\"count\":3"), streak.getPayload());

        activityIngestor.ingest(failedLogin("alice", "10.0.0.9", "f4", now.plusSeconds(1)));
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK)),
                "la clave de la racha es la ventana, no el fallo");
        // el motor ve los cuatro fallos y la racha una vez
        verify(ruleEngine, times(5)).evaluate(any(), any());
    }

    @Test
    void threeFailuresFromOneIpWithDifferentUsersDeriveAnIpStreak() {
        Instant now = Instant.now();
        activityIngestor.ingest(failedLogin("alice", "10.0.0.1", "i1", now.minusSeconds(90)));
        activityIngestor.ingest(failedLogin("bob", "10.0.0.1", "i2", now.minusSeconds(60)));
        activityIngestor.ingest(failedLogin("carol", "10.0.0.1", "i3", now.minusSeconds(30)));

        List<ActivityEvent> streaks = activityEventRepository.findAll(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK));
        assertEquals(1, streaks.size());
        assertEquals("streak:ip:10.0.0.1:" + now.minusSeconds(90).toEpochMilli(), streaks.getFirst().getSourceEventId());
        assertEquals("10.0.0.1", streaks.getFirst().getIpAddress().getHostAddress());
    }

    @Test
    void failuresOutsideTheWindowDoNotCount() {
        Instant now = Instant.now();
        activityIngestor.ingest(failedLogin("alice", null, "w1", now.minus(Duration.ofMinutes(30))));
        activityIngestor.ingest(failedLogin("alice", null, "w2", now.minus(Duration.ofMinutes(20))));
        activityIngestor.ingest(failedLogin("alice", null, "w3", now));

        assertEquals(0, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK)));
    }

    // --- el correlador de usuarios ---

    @Test
    void theKeycloakLineOfAChangeMadeFromTheApplicationIsSupersededWhicheverArrivesFirst() {
        Instant now = Instant.now();
        // mto-users primero, Keycloak despues: lo habitual, porque el lector sondea cada 20 s.
        ActivityEvent users = activityIngestor.ingest(usersLine("u-1", ActivityTypes.USERS_PROFILE_ASSIGNED, now.minusSeconds(10),
                Map.of("profile", "mto-users-viewer"))).orElseThrow();
        ActivityEvent keycloak = activityIngestor.ingest(keycloakAdminLine("k-1", ActivityTypes.USERS_ADMIN_REALM_ROLES_ADDED,
                ActorKind.SERVICE, "user", "id-alice", now)).orElseThrow();
        assertEquals(users.getId(), reload(keycloak.getId()).getSupersededBy());
        assertNull(reload(users.getId()).getSupersededBy(), "la linea con la persona es la que queda");

        // Keycloak primero, mto-users despues.
        ActivityEvent keycloakFirst = activityIngestor.ingest(keycloakAdminLine("k-2", ActivityTypes.USERS_ADMIN_USER_UPDATED,
                ActorKind.SERVICE, "user", "id-alice", now.plusSeconds(20))).orElseThrow();
        assertNull(reload(keycloakFirst.getId()).getSupersededBy());
        ActivityEvent usersAfter = activityIngestor.ingest(usersLine("u-2", ActivityTypes.USERS_USER_DISABLED, now.plusSeconds(25), Map.of())).orElseThrow();
        assertEquals(usersAfter.getId(), reload(keycloakFirst.getId()).getSupersededBy());

        // Fuera de la ventana, o hecho desde la consola (una persona, no la cuenta de servicio): no se funde.
        ActivityEvent late = activityIngestor.ingest(keycloakAdminLine("k-3", ActivityTypes.USERS_ADMIN_USER_UPDATED,
                ActorKind.SERVICE, "user", "id-alice", now.plus(Duration.ofMinutes(10)))).orElseThrow();
        ActivityEvent console = activityIngestor.ingest(keycloakAdminLine("k-4", ActivityTypes.USERS_ADMIN_USER_UPDATED,
                ActorKind.PERSON, "user", "id-alice", now.plusSeconds(26))).orElseThrow();
        assertNull(reload(late.getId()).getSupersededBy());
        assertNull(reload(console.getId()).getSupersededBy());

        assertEquals(4, activityEventRepository.count(ActivityEventSpecification.categoryEquals(ActivityCategory.USERS)
                        .and(ActivityEventSpecification.notSuperseded())),
                "las consultas esconden lo fundido: quedan las dos de mto-users, la tardia y la de la consola");
        assertEquals(6, activityEventRepository.count(ActivityEventSpecification.categoryEquals(ActivityCategory.USERS)));
    }

    @Test
    void aSessionClosedFromTheApplicationIsMatchedWithTheKeycloakLineOfThatSessionByItsPayload() {
        Instant now = Instant.now();
        ActivityEvent users = activityIngestor.ingest(usersLine("u-s", ActivityTypes.USERS_SESSION_REVOKED, now, Map.of("session", "sess-1"))).orElseThrow();
        ActivityEvent keycloak = activityIngestor.ingest(keycloakAdminLine("k-s", ActivityTypes.USERS_ADMIN_SESSION_DELETED,
                ActorKind.SERVICE, "session", "sess-1", now.plusSeconds(15))).orElseThrow();
        ActivityEvent other = activityIngestor.ingest(keycloakAdminLine("k-o", ActivityTypes.USERS_ADMIN_SESSION_DELETED,
                ActorKind.SERVICE, "session", "sess-2", now.plusSeconds(16))).orElseThrow();

        assertEquals(users.getId(), reload(keycloak.getId()).getSupersededBy());
        assertNull(reload(other.getId()).getSupersededBy(), "otra sesion es otro cambio");
    }

    // --- rafagas ---

    @Test
    void eventsOfOneKeyPileUpInOneOpenBurstWithABoundedSample() {
        Actor actor = Actor.person("config.responsable", "u1");
        Instant now = Instant.now();
        burstAggregator.record("mto-configuration", "profile", "updated", actor, "job-1", "p1", now.minusSeconds(3));
        burstAggregator.record("mto-configuration", "profile", "updated", actor, "job-1", "p2", now.minusSeconds(2));
        burstAggregator.record("mto-configuration", "profile", "updated", actor, "job-1", "p3", now.minusSeconds(1));
        burstAggregator.record("mto-configuration", "profile", "updated", Actor.person("otra", "u2"), "job-1", "p4", now);

        List<ActivityBurst> open = activityBurstRepository.findAll();
        assertEquals(2, open.size());
        ActivityBurst big = open.stream().filter(burst -> burst.getEventCount() == 3).findFirst().orElseThrow();
        assertEquals(ActivityBurstStatus.OPEN, big.getStatus());
        assertEquals("[\"p1\", \"p2\"]", big.getSampleIds().replace("\",\"", "\", \""), "la muestra para en sample-size");
        assertEquals(now.minusSeconds(1).toEpochMilli(), big.getLastEventAt().toEpochMilli());
        assertEquals("job-1", big.getCorrelationId());
        assertEquals("config.responsable", big.getActorUsername());
    }

    @Test
    void closingABurstWritesOneLineWithTheCountAndTheSample() {
        burstAggregator.record("mto-configuration", "station", "updated", Actor.system(), null, "s1", Instant.now().minusSeconds(5));
        burstAggregator.record("mto-configuration", "station", "updated", Actor.system(), null, "s2", Instant.now().minusSeconds(4));

        assertEquals(1, burstAggregator.closeExpired());
        assertEquals(0, burstAggregator.closeExpired());

        entityManager.clear();
        ActivityBurst closed = activityBurstRepository.findAll().getFirst();
        assertEquals(ActivityBurstStatus.CLOSED, closed.getStatus());
        assertNotNull(closed.getClosedAt());
        List<ActivityEvent> lines = activityEventRepository.findAll(ActivityEventSpecification.typeEquals("configuration.station.updated"));
        assertEquals(1, lines.size());
        assertEquals(2, lines.getFirst().getEventCount());
        assertEquals("burst:" + closed.getId(), lines.getFirst().getSourceEventId());
        assertEquals("2 station", lines.getFirst().getSubjectLabel());
        assertTrue(lines.getFirst().getPayload().contains("s1"));

        burstAggregator.record("mto-configuration", "station", "updated", Actor.system(), null, "s3", Instant.now());
        assertEquals(2, activityBurstRepository.count(), "cerrada la anterior, el siguiente evento abre otra");
    }

    /**
     * Una importacion de once minutos es UNA linea: la rafaga de un trabajo (con correlationId) no se
     * corta a los diez minutos de abrirse, sino cuando deja de recibir eventos, con un tope propio
     * (cuatro horas) por si no parase nunca. Una sin correlationId si se corta a los diez minutos. Los
     * ultimos eventos llegan en el futuro para que la inactividad (PT0S en este test) no cierre ninguna.
     */
    @Test
    void aJobBurstOutlivesTheMaxWindowButNotItsOwnCapAndAnUncorrelatedOneDoesNot() {
        Instant now = Instant.now();
        Instant stillArriving = now.plusSeconds(60);
        burstAggregator.record("mto-configuration", "profile", "created", Actor.system(), "job-largo", "p1", now.minus(Duration.ofMinutes(11)));
        burstAggregator.record("mto-configuration", "profile", "created", Actor.system(), "job-largo", "p2", stillArriving);
        burstAggregator.record("mto-configuration", "profile", "created", Actor.system(), null, "p3", now.minus(Duration.ofMinutes(11)));
        burstAggregator.record("mto-configuration", "profile", "created", Actor.system(), null, "p4", stillArriving);
        burstAggregator.record("mto-configuration", "profile", "created", Actor.system(), "job-sin-fin", "p5", now.minus(Duration.ofHours(5)));
        burstAggregator.record("mto-configuration", "profile", "created", Actor.system(), "job-sin-fin", "p6", stillArriving);

        assertEquals(2, burstAggregator.closeExpired());

        entityManager.clear();
        Map<String, ActivityBurstStatus> statusByCorrelation = new HashMap<>();
        activityBurstRepository.findAll().forEach(burst -> statusByCorrelation.put(String.valueOf(burst.getCorrelationId()), burst.getStatus()));
        assertEquals(ActivityBurstStatus.OPEN, statusByCorrelation.get("job-largo"), "el trabajo sigue mandando: sigue abierta");
        assertEquals(ActivityBurstStatus.CLOSED, statusByCorrelation.get("null"), "sin correlationId, diez minutos");
        assertEquals(ActivityBurstStatus.CLOSED, statusByCorrelation.get("job-sin-fin"), "el tope de las de un trabajo");
        assertEquals(2, activityEventRepository.count(ActivityEventSpecification.typeEquals("configuration.profile.created")));
    }

    /**
     * Dos cerradores a la vez sobre la misma rafaga: {@code for update skip locked} deja pasar a uno y
     * el otro no la ve. Sin transaccion de test a proposito, porque la carrera necesita dos
     * transacciones reales; lo que escribe se borra al final.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConcurrentClosersCloseABurstOnlyOnce() throws Exception {
        transactionTemplate.executeWithoutResult(status -> burstAggregator.record("mto-configuration", "cantilever", "created",
                Actor.system(), "race", "c1", Instant.now().minusSeconds(5)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> closers = List.of(
                    pool.submit(() -> { start.await(); return burstAggregator.closeExpired(); }),
                    pool.submit(() -> { start.await(); return burstAggregator.closeExpired(); }));
            start.countDown();
            int closed = closers.get(0).get() + closers.get(1).get();

            assertEquals(1, closed);
            assertEquals(1, activityEventRepository.count(ActivityEventSpecification.typeEquals("configuration.cantilever.created")));
        } finally {
            pool.shutdownNow();
            transactionTemplate.executeWithoutResult(status -> {
                activityEventRepository.deleteAll(activityEventRepository.findAll(
                        ActivityEventSpecification.typeEquals("configuration.cantilever.created")));
                activityBurstRepository.deleteAll();
            });
        }
    }

    // --- frenos ---

    @Test
    void aThrottleFiresOncePerKeyAndWindowAndAgainWhenTheWindowIsOver() {
        assertTrue(throttleGate.tryAcquire("access-login-streak", "username:alice", Duration.ofMinutes(30)));
        assertFalse(throttleGate.tryAcquire("access-login-streak", "username:alice", Duration.ofMinutes(30)));
        assertTrue(throttleGate.tryAcquire("access-login-streak", "username:bob", Duration.ofMinutes(30)));
        assertTrue(throttleGate.tryAcquire("otra-regla", "username:alice", Duration.ofMinutes(30)));
        assertTrue(throttleGate.tryAcquire("sin-dimension", null, Duration.ofMinutes(1)));
        assertFalse(throttleGate.tryAcquire("sin-dimension", " ", Duration.ofMinutes(1)));

        assertEquals(1, ruleThrottleRepository.tryAcquire("vencida", "x", 0));
        assertEquals(1, ruleThrottleRepository.tryAcquire("vencida", "x", 0), "una ventana ya cumplida vuelve a disparar");
    }

    // --- el arrendamiento del lector ---

    @Test
    void aSourceIsLeasedByOneReaderAtATimeAndTheWatermarkOnlyMovesForward() {
        Optional<SourceCursorService.Lease> lease = sourceCursorService.acquire(SourceKind.KEYCLOAK_LOGIN);
        assertTrue(lease.isPresent());
        assertNull(lease.get().lastEventTime(), "primer arranque: sin marca");
        assertTrue(sourceCursorService.acquire(SourceKind.KEYCLOAK_LOGIN).isEmpty(), "arrendada");
        assertTrue(sourceCursorService.acquire(SourceKind.KEYCLOAK_ADMIN).isPresent(), "la otra fuente es independiente");

        Instant t1 = Instant.parse("2026-09-28T10:00:00Z");
        sourceCursorService.release(lease.get(), t1, null);
        SourceCursor cursor = cursor(SourceKind.KEYCLOAK_LOGIN);
        assertNull(cursor.getLeaseOwner());
        assertEquals(t1, cursor.getLastEventTime());
        assertNotNull(cursor.getLastSuccessAt());
        assertNotNull(cursor.getLastPollAt());

        SourceCursorService.Lease second = sourceCursorService.acquire(SourceKind.KEYCLOAK_LOGIN).orElseThrow();
        assertEquals(t1, second.lastEventTime());
        sourceCursorService.release(second, t1.minusSeconds(60), "DirectoryUnavailableException: down");
        cursor = cursor(SourceKind.KEYCLOAK_LOGIN);
        assertEquals(t1, cursor.getLastEventTime(), "nunca hacia atras");
        assertEquals("DirectoryUnavailableException: down", cursor.getLastError());

        assertEquals(0, sourceCursorRepository.release("KEYCLOAK_LOGIN", "nadie", t1, null), "solo suelta quien arrienda");
    }

    @Test
    void anExpiredLeaseCanBeTakenByAnotherReader() {
        assertEquals(1, sourceCursorRepository.acquireLease("KEYCLOAK_ADMIN", "caido", Instant.now().minusSeconds(1)));
        assertEquals(1, sourceCursorRepository.acquireLease("KEYCLOAK_ADMIN", "vivo", Instant.now().plusSeconds(120)));
        assertEquals("vivo", cursor(SourceKind.KEYCLOAK_ADMIN).getLeaseOwner());
    }

    // --- purga ---

    @Test
    void thePurgeDeletesByCategoryAndAgeAndLeavesTheRest() {
        Instant now = Instant.now();
        activityIngestor.ingest(failedLogin("alice", "10.0.0.1", "old-access", now));
        activityIngestor.ingest(failedLogin("alice", "10.0.0.1", "new-access", now));
        activityIngestor.ingest(ActivityEventDraft.builder().source("mto-configuration", "old-config").type("configuration.track.deleted")
                .occurredAt(now).build());
        burstAggregator.record("mto-configuration", "profile", "updated", Actor.system(), null, "p", now.minusSeconds(5));
        burstAggregator.closeExpired();
        ruleThrottleRepository.tryAcquire("r", "d", 0);
        entityManager.createNativeQuery("update activity_event set recorded_at = now() - interval '100 days' where source_event_id in ('old-access', 'old-config')").executeUpdate();
        entityManager.createNativeQuery("update activity_burst set closed_at = now() - interval '2 days'").executeUpdate();
        entityManager.createNativeQuery("update rule_throttle set until = now() - interval '2 days'").executeUpdate();

        Map<String, Integer> deleted = retentionPurge.purge();

        assertEquals(1, deleted.get("activity_event.ACCESS"), "90 dias para los accesos");
        assertEquals(0, deleted.get("activity_event.CONFIGURATION"), "400 dias para la configuracion");
        assertEquals(1, deleted.get("activity_burst"));
        assertEquals(1, deleted.get("rule_throttle"));
        entityManager.clear();
        assertTrue(activityEventRepository.findBySourceServiceAndSourceEventId("keycloak-login", "new-access").isPresent());
        assertTrue(activityEventRepository.findBySourceServiceAndSourceEventId("keycloak-login", "old-access").isEmpty());
        assertTrue(activityEventRepository.findBySourceServiceAndSourceEventId("mto-configuration", "old-config").isPresent());
    }

    // --- soporte ---

    private static ActivityEventDraft failedLogin(String username, String ip, String fingerprint, Instant at) {
        return ActivityEventDraft.builder()
                .source("keycloak-login", fingerprint)
                .type(ActivityTypes.ACCESS_LOGIN_FAILED)
                .severity(ActivitySeverity.WARNING)
                .occurredAt(at)
                .actor(Actor.person(username, "id-" + username))
                .subject(Subject.of("user", "id-" + username, username))
                .ipAddress(ip)
                .payload(Map.of("username", username, "error", "invalid_user_credentials"))
                .build();
    }

    private static ActivityEventDraft usersLine(String operationId, String type, Instant at, Map<String, Object> detail) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("targetUserId", "id-alice", "targetUsername", "alice"));
        payload.putAll(detail);
        return ActivityEventDraft.builder()
                .source("mto-users", operationId)
                .type(type)
                .occurredAt(at)
                .actor(Actor.person("usuarios.responsable", "id-admin"))
                .subject(Subject.of("user", "id-alice", "alice"))
                .payload(payload)
                .build();
    }

    private static ActivityEventDraft keycloakAdminLine(String fingerprint, String type, ActorKind actorKind, String subjectType,
                                                        String subjectId, Instant at) {
        Actor actor = actorKind == ActorKind.SERVICE ? Actor.service("mto-users-svc", "svc-id") : new Actor(ActorKind.PERSON, null, "admin-id");
        return ActivityEventDraft.builder()
                .source("keycloak-admin", fingerprint)
                .type(type)
                .occurredAt(at)
                .actor(actor)
                .subject(Subject.of(subjectType, subjectId))
                .payload(Map.of("clientId", actorKind == ActorKind.SERVICE ? "mto-users-svc" : "admin-cli"))
                .build();
    }

    private ActivityEvent reload(UUID id) {
        entityManager.clear();
        return activityEventRepository.findById(id).orElseThrow();
    }

    private SourceCursor cursor(SourceKind kind) {
        entityManager.clear();
        return sourceCursorRepository.findById(kind).orElseThrow();
    }

    private static java.net.InetAddress ip(String literal) {
        try {
            return java.net.InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
