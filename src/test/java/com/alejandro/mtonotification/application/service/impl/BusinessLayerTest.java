package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakClient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakRole;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakUser;
import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceActor;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.AudienceResolver;
import com.alejandro.mtonotification.application.service.BurstAggregator;
import com.alejandro.mtonotification.application.service.DeliveryChannel;
import com.alejandro.mtonotification.application.service.DeliveryDispatcher;
import com.alejandro.mtonotification.application.service.DeliveryRelayService;
import com.alejandro.mtonotification.application.service.DerivedEventDetector;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.application.service.KeycloakDirectoryClient;
import com.alejandro.mtonotification.application.service.KeycloakEventsClient;
import com.alejandro.mtonotification.application.service.KeycloakEventsPoller;
import com.alejandro.mtonotification.application.service.NotificationFactory;
import com.alejandro.mtonotification.application.service.RuleEngine;
import com.alejandro.mtonotification.application.service.RuleRepository;
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
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.domain.model.EventTypeMatcher;
import com.alejandro.mtonotification.domain.model.NotificationRule;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los servicios con dobles: el motor de reglas, la ingesta, los adaptadores de Keycloak y de
 * datos maestros, el detector de rachas, el despachador y el resolutor de audiencias. Lo que
 * decide la base de datos (idempotencia, rafagas, frenos) se prueba contra PostgreSQL en los
 * {@code *DataJpaTest}.
 */
class BusinessLayerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static NotificationProperties properties() {
        return new NotificationProperties("classpath:notification-rules.yml", null, null, null, null, null, null, null);
    }

    static KeycloakProperties keycloakProperties() {
        return new KeycloakProperties("http://keycloak:8080", "mto", "mto-services", null, null, null);
    }

    static ActivityEvent event(String type, ActivityCategory category, ActivitySeverity severity, String username, String ip) {
        ActivityEvent event = new TestEvent();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        event.setSourceService("test");
        event.setSourceEventId(UUID.randomUUID().toString());
        event.setType(type);
        event.setCategory(category);
        event.setSeverity(severity);
        event.setOccurredAt(Instant.parse("2026-09-28T10:00:00Z"));
        event.setRecordedAt(Instant.now());
        event.setActorKind(username == null ? ActorKind.SYSTEM : ActorKind.PERSON);
        event.setActorUsername(username);
        event.setActorId(username == null ? null : "id-" + username);
        event.setSubjectType("user");
        event.setSubjectId(username == null ? null : "id-" + username);
        event.setEventCount(1);
        event.setPayload("{}");
        try {
            event.setIpAddress(ip == null ? null : InetAddress.getByName(ip));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        return event;
    }

    static NotificationRule rule(String key, String event, String when, List<String> audiences, List<String> channels,
                                 String title, NotificationRule.Throttle throttle) {
        return new NotificationRule(key, EventTypeMatcher.of(List.of(event)), when, null, audiences, channels, title, null, null, throttle);
    }

    private static final class TestEvent extends ActivityEvent {
    }

    // ---------------------------------------------------------------------------------------
    // Motor de reglas
    // ---------------------------------------------------------------------------------------

    @Nested
    class Rules {

        private final RuleRepository repository = mock(RuleRepository.class);
        private final ThrottleGate throttle = mock(ThrottleGate.class);
        private final NotificationFactory factory = mock(NotificationFactory.class);
        private final RuleEngineImpl engine = new RuleEngineImpl(repository, new RuleExpressionEvaluator(), throttle, factory);

        @Test
        void aMatchingRuleRendersItsTemplatesAndAudiences() {
            when(repository.variables()).thenReturn(Map.of("limit", 3));
            when(repository.rules()).thenReturn(List.of(rule("streak", ActivityTypes.ACCESS_LOGIN_STREAK,
                    "payload.count >= vars['limit']", List.of("PROFILE:mto-ops", "USER:#{payload.value}", "USER:#{payload.missing}"),
                    List.of("inbox", "email"), "Racha de #{payload.value}: #{payload.count} fallos", null)));
            when(factory.create(any())).thenAnswer(invocation -> Notification.builder().build());

            List<Notification> created = engine.evaluate(
                    event(ActivityTypes.ACCESS_LOGIN_STREAK, ActivityCategory.ACCESS, ActivitySeverity.CRITICAL, "alice", null),
                    Map.of("value", "alice", "count", 3));

            assertEquals(1, created.size());
            ArgumentCaptor<NotificationFactory.NotificationDraft> draft = ArgumentCaptor.forClass(NotificationFactory.NotificationDraft.class);
            verify(factory).create(draft.capture());
            assertEquals("Racha de alice: 3 fallos", draft.getValue().title());
            assertEquals(List.of(Audience.profile("mto-ops"), Audience.user("alice")), draft.getValue().audiences(),
                    "una audiencia que queda en blanco se omite");
            assertEquals(ActivitySeverity.CRITICAL, draft.getValue().severity(), "sin gravedad en la regla, la del evento");
            assertEquals(List.of("inbox", "email"), draft.getValue().channels());
        }

        @Test
        void aFalseConditionOrAClosedThrottleCreatesNothing() {
            when(repository.variables()).thenReturn(Map.of());
            when(repository.rules()).thenReturn(List.of(
                    rule("cond", ActivityTypes.ACCESS_LOGIN, "payload.count > 10", List.of("PROFILE:mto-ops"), List.of(), "t", null),
                    rule("thr", ActivityTypes.ACCESS_LOGIN, null, List.of("PROFILE:mto-ops"), List.of(), "t",
                            new NotificationRule.Throttle(Duration.ofMinutes(5), "#{event.actorUsername}"))));
            when(throttle.tryAcquire(eq("thr"), eq("alice"), eq(Duration.ofMinutes(5)))).thenReturn(false);

            assertTrue(engine.evaluate(event(ActivityTypes.ACCESS_LOGIN, ActivityCategory.ACCESS, ActivitySeverity.INFO, "alice", null),
                    Map.of("count", 1)).isEmpty());
            verify(factory, never()).create(any());
        }

        @Test
        void aRuleThatCannotBeEvaluatedIsSkippedWithoutBreakingTheOthers() {
            when(repository.variables()).thenReturn(Map.of());
            when(repository.rules()).thenReturn(List.of(
                    rule("broken", ActivityTypes.ACCESS_LOGIN, "payload.count.nonsense()", List.of("PROFILE:mto-ops"), List.of(), "t", null),
                    rule("fine", ActivityTypes.ACCESS_LOGIN, null, List.of("PROFILE:mto-ops"), List.of(), "t", null)));
            when(factory.create(any())).thenAnswer(invocation -> Notification.builder().build());

            assertEquals(1, engine.evaluate(event(ActivityTypes.ACCESS_LOGIN, ActivityCategory.ACCESS, ActivitySeverity.INFO, "alice", null),
                    Map.of("count", 1)).size());
        }

        @Test
        void aMissingPayloadKeyIsNullNotAnError() {
            RuleExpressionEvaluator evaluator = new RuleExpressionEvaluator();
            Map<String, Object> scope = Map.of("payload", Map.of("a", 1), "event", Map.of(), "vars", Map.of());
            assertFalse(evaluator.condition("payload.b == 1", scope));
            assertTrue(evaluator.condition("payload.b == null", scope));
            assertEquals("x--", evaluator.render("x-#{payload.b}-", scope));
            assertEquals("literal", evaluator.render("literal", scope));
            assertEquals("A", evaluator.render("#{payload.c ?: 'A'}", scope));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ingesta y detectores
    // ---------------------------------------------------------------------------------------

    @Nested
    class Ingestion {

        private final ActivityEventRepository repository = mock(ActivityEventRepository.class);
        private final RuleEngine ruleEngine = mock(RuleEngine.class);
        private final DerivedEventDetector detector = mock(DerivedEventDetector.class);
        private final ActivityIngestorImpl ingestor = new ActivityIngestorImpl(repository, ruleEngine, List.of(detector), new JsonPayloads(JSON));

        private final ActivityEventDraft draft = ActivityEventDraft.builder()
                .source("keycloak-login", "fp-1").type(ActivityTypes.ACCESS_LOGIN).occurredAt(Instant.now())
                .actor(Actor.person("alice", "u1")).ipAddress("10.0.0.1").payload(Map.of("username", "alice")).build();

        @Test
        void aNewLineRunsTheRulesAndTheDetectors() {
            ActivityEvent stored = event(ActivityTypes.ACCESS_LOGIN, ActivityCategory.ACCESS, ActivitySeverity.INFO, "alice", "10.0.0.1");
            when(repository.insertIfMissing(eq("keycloak-login"), eq("fp-1"), eq("ACCESS"), eq(ActivityTypes.ACCESS_LOGIN), eq("INFO"),
                    any(), eq("PERSON"), eq("alice"), eq("u1"), any(), any(), any(), any(), eq("10.0.0.1"), eq(1), anyString())).thenReturn(1);
            when(repository.findBySourceServiceAndSourceEventId("keycloak-login", "fp-1")).thenReturn(Optional.of(stored));

            Optional<ActivityEvent> result = ingestor.ingest(draft);

            assertTrue(result.isPresent());
            verify(ruleEngine).evaluate(eq(stored), eq(Map.of("username", "alice")));
            verify(detector).afterIngested(stored, draft);
        }

        @Test
        void aRepeatedLineRunsNothing() {
            when(repository.insertIfMissing(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyInt(), any())).thenReturn(0);

            assertTrue(ingestor.ingest(draft).isEmpty());
            verify(ruleEngine, never()).evaluate(any(), any());
            verify(detector, never()).afterIngested(any(), any());
        }

        @Test
        void threeFailuresOfTheSameUserMakeOneStreakWithAStableKey() {
            ActivityIngestor derived = mock(ActivityIngestor.class);
            when(derived.ingest(any())).thenReturn(Optional.empty());
            FailedLoginStreakDetector detectorUnderTest = new FailedLoginStreakDetector(repository, derived, properties());
            ActivityEvent third = event(ActivityTypes.ACCESS_LOGIN_FAILED, ActivityCategory.ACCESS, ActivitySeverity.WARNING, "alice", "10.0.0.1");
            Instant earliest = third.getOccurredAt().minus(Duration.ofMinutes(4));
            when(repository.countByTypeAndActorInWindow(eq(ActivityTypes.ACCESS_LOGIN_FAILED), eq("alice"), any(), any())).thenReturn(3L);
            when(repository.earliestByTypeAndActorInWindow(eq(ActivityTypes.ACCESS_LOGIN_FAILED), eq("alice"), any(), any())).thenReturn(earliest);
            when(repository.countByTypeAndIpInWindow(eq(ActivityTypes.ACCESS_LOGIN_FAILED), eq("10.0.0.1"), any(), any())).thenReturn(2L);

            detectorUnderTest.afterIngested(third, draft);

            ArgumentCaptor<ActivityEventDraft> streak = ArgumentCaptor.forClass(ActivityEventDraft.class);
            verify(derived, times(1)).ingest(streak.capture());
            assertEquals(ActivityTypes.ACCESS_LOGIN_STREAK, streak.getValue().type());
            assertEquals("streak:username:alice:" + earliest.toEpochMilli(), streak.getValue().sourceEventId(),
                    "el cuarto fallo en la misma ventana calcula la misma clave y no inserta nada");
            assertEquals(ActivitySeverity.CRITICAL, streak.getValue().severity());
            assertEquals(3L, streak.getValue().payload().get("count"));
            assertEquals("alice", streak.getValue().actor().username());
        }

        @Test
        void belowTheThresholdNothingIsDerivedAndOnlyFailedLoginsAreLookedAt() {
            ActivityIngestor derived = mock(ActivityIngestor.class);
            FailedLoginStreakDetector detectorUnderTest = new FailedLoginStreakDetector(repository, derived, properties());
            when(repository.countByTypeAndActorInWindow(any(), any(), any(), any())).thenReturn(2L);
            when(repository.countByTypeAndIpInWindow(any(), any(), any(), any())).thenReturn(1L);

            detectorUnderTest.afterIngested(event(ActivityTypes.ACCESS_LOGIN_FAILED, ActivityCategory.ACCESS, ActivitySeverity.WARNING, "alice", "10.0.0.1"), draft);
            detectorUnderTest.afterIngested(event(ActivityTypes.ACCESS_LOGIN, ActivityCategory.ACCESS, ActivitySeverity.INFO, "alice", "10.0.0.1"), draft);

            verify(derived, never()).ingest(any());
            verify(repository, times(1)).countByTypeAndActorInWindow(any(), any(), any(), any());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Datos maestros: directo o rafaga
    // ---------------------------------------------------------------------------------------

    @Nested
    class MasterData {

        private final ActivityIngestor ingestor = mock(ActivityIngestor.class);
        private final BurstAggregator bursts = mock(BurstAggregator.class);
        private final MasterDataSourceAdapter adapter = new MasterDataSourceAdapter(ingestor, bursts, properties());
        private final SourceEventContext context = new SourceEventContext("master-data", 7L, "mto.master-data.profile.updated");

        private SourceEnvelope envelope(String entity, String operation, SourceActor actor, String correlationId) {
            return new SourceEnvelope(UUID.randomUUID(), entity + "-42", "mto-configuration", Instant.parse("2026-09-28T10:00:00Z"),
                    "MASTER_DATA_" + entity.toUpperCase().replace('-', '_') + "_" + operation.toUpperCase(),
                    Map.of("entityName", entity, "entityId", "42", "operation", operation.toUpperCase(),
                            "values", Map.of("code", "P-42", "name", "Perfil 42")), "hash", actor, correlationId);
        }

        @Test
        void anUpdateOfAProfileGoesToABurstWithActorAndCorrelation() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());
            adapter.handle(envelope("profile", "updated", new SourceActor("u1", "alice", "PERSON"), "job-9"), context);

            verify(bursts).record(eq("mto-configuration"), eq("profile"), eq("updated"), eq(Actor.person("alice", "u1")),
                    eq("job-9"), eq("42"), eq(Instant.parse("2026-09-28T10:00:00Z")));
            verify(ingestor, never()).ingest(any());
        }

        @Test
        void aDeletedTrackAndACreatedPackageAreDirectLines() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());
            adapter.handle(envelope("track", "deleted", null, null), context);
            adapter.handle(envelope("execution-package", "created", null, null), context);

            ArgumentCaptor<ActivityEventDraft> drafts = ArgumentCaptor.forClass(ActivityEventDraft.class);
            verify(ingestor, times(2)).ingest(drafts.capture());
            assertEquals("configuration.track.deleted", drafts.getAllValues().get(0).type());
            assertEquals(ActivitySeverity.WARNING, drafts.getAllValues().get(0).severity());
            assertEquals("P-42", drafts.getAllValues().get(0).subject().label());
            assertEquals("configuration.execution-package.created", drafts.getAllValues().get(1).type());
            verify(bursts, never()).record(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void aDeletedCantileverIsAggregatedAndAnUnknownOperationIsPermanent() {
            adapter.handle(envelope("cantilever", "deleted", null, null), context);
            verify(bursts).record(eq("mto-configuration"), eq("cantilever"), eq("deleted"), any(), any(), eq("42"), any());

            SourceEnvelope broken = new SourceEnvelope(UUID.randomUUID(), "x", "mto-configuration", Instant.now(), "X",
                    Map.of("entityName", "profile", "operation", "RENAMED"), null, null, null);
            assertThrows(UnprocessableSourceEventException.class, () -> adapter.handle(broken, context));
            SourceEnvelope nameless = new SourceEnvelope(UUID.randomUUID(), "x", "mto-configuration", Instant.now(), "X",
                    Map.of("operation", "CREATED"), null, null, null);
            assertThrows(UnprocessableSourceEventException.class, () -> adapter.handle(nameless, context));
        }

        @Test
        void theDispatcherRoutesBySourceAndRefusesTwoAdaptersForOne() {
            DispatchingSourceEventHandler handler = new DispatchingSourceEventHandler(List.of(adapter));
            assertThrows(UnprocessableSourceEventException.class, () -> handler.handle(envelope("profile", "updated", null, null),
                    new SourceEventContext("maintenance", null, null)));
            assertThrows(IllegalStateException.class, () -> new DispatchingSourceEventHandler(List.of(adapter, adapter)));
        }

        @Test
        void theIdempotentProcessorRecordsTheFailureAfterTheAttemptAndRethrows() {
            InboxMessageService inbox = mock(InboxMessageService.class);
            when(inbox.process(any(), any())).thenThrow(new IllegalStateException("database is down"));
            IdempotentSourceEventProcessor processor = new IdempotentSourceEventProcessor(inbox, (envelope, ctx) -> { });
            InboxMessageCommand command = new InboxMessageCommand("m1", "mto-configuration", "X", null, null, null, null, null, "h", "{}", null);

            assertThrows(IllegalStateException.class, () -> processor.process(command, envelope("profile", "updated", null, null), context));
            verify(inbox).recordFailure(eq(command), any(IllegalStateException.class));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Keycloak: adaptadores y lector
    // ---------------------------------------------------------------------------------------

    @Nested
    class Keycloak {

        private final KeycloakLoginEventAdapter loginAdapter = new KeycloakLoginEventAdapter();
        private final KeycloakAdminEventAdapter adminAdapter = new KeycloakAdminEventAdapter(keycloakProperties(), JSON);

        @Test
        void aLoginErrorBecomesAFailedAccessWithOnlyWhitelistedDetails() {
            KeycloakEvent event = new KeycloakEvent(1_700_000_000_000L, "LOGIN_ERROR", "realm", "mto-frontend", "u1", null, "10.0.0.1",
                    "invalid_user_credentials", Map.of("username", "alice", "auth_method", "openid-connect", "code_id", "secret-code",
                            "redirect_uri", "http://x"), "{}");
            ActivityEventDraft draft = loginAdapter.toDraft(event).orElseThrow();

            assertEquals(ActivityTypes.ACCESS_LOGIN_FAILED, draft.type());
            assertEquals(ActivitySeverity.WARNING, draft.severity());
            assertEquals("alice", draft.actor().username());
            assertEquals("10.0.0.1", draft.ipAddress());
            assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), draft.occurredAt());
            assertEquals("invalid_user_credentials", draft.payload().get("error"));
            assertEquals("openid-connect", draft.payload().get("auth_method"));
            assertFalse(draft.payload().containsKey("code_id"));
            assertFalse(draft.payload().containsKey("redirect_uri"));
            assertEquals(KeycloakLoginEventAdapter.fingerprint(event), draft.sourceEventId());
            assertEquals(KeycloakLoginEventAdapter.SOURCE_SERVICE, draft.sourceService());
        }

        @Test
        void machineNoiseIsIgnoredAndUnknownTypesBecomeAccessOther() {
            KeycloakEvent noise = new KeycloakEvent(1L, "CLIENT_LOGIN", null, "mto-maintenance-svc", null, null, null, null, Map.of(), null);
            assertTrue(loginAdapter.toDraft(noise).isEmpty());
            KeycloakEvent unknown = new KeycloakEvent(1L, "SOMETHING_NEW", null, null, "u1", null, null, null, Map.of(), null);
            assertEquals(ActivityTypes.ACCESS_OTHER, loginAdapter.toDraft(unknown).orElseThrow().type());
            KeycloakEvent lockout = new KeycloakEvent(1L, "USER_DISABLED_BY_PERMANENT_LOCKOUT", null, null, "u1", null, null, null,
                    Map.of("username", "bob"), null);
            ActivityEventDraft draft = loginAdapter.toDraft(lockout).orElseThrow();
            assertEquals(ActivityTypes.ACCESS_LOCKOUT, draft.type());
            assertEquals(ActivitySeverity.CRITICAL, draft.severity());
            assertEquals(true, draft.payload().get("permanent"));
        }

        @Test
        void anAdminEventIsClassifiedByItsPathAndItsClient() {
            KeycloakAdminEvent fromApp = new KeycloakAdminEvent(1L, "realm", new KeycloakAdminEvent.AuthDetails("realm", "mto-users-svc", "svc", "10.0.0.2"),
                    "UPDATE", "USER", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", null, null, "{}");
            ActivityEventDraft app = adminAdapter.toDraft(fromApp).orElseThrow();
            assertEquals(ActivityTypes.USERS_ADMIN_USER_UPDATED, app.type());
            assertEquals(ActorKind.SERVICE, app.actor().kind());
            assertEquals("service-account-mto-users-svc", app.actor().username());
            assertEquals("8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", app.subject().id());
            assertEquals("mto-users-svc", app.payload().get("clientId"));
            assertNull(app.ipAddress(), "la IP no vive fuera de ACCESS");

            KeycloakAdminEvent fromConsole = new KeycloakAdminEvent(1L, "realm", new KeycloakAdminEvent.AuthDetails("master", "security-admin-console", "admin-id", null),
                    "CREATE", "CLIENT_ROLE_MAPPING", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21/role-mappings/clients/abc",
                    "[{\"id\":\"r1\",\"name\":\"stock-read\"},{\"id\":\"r2\",\"name\":\"stock-write\"}]", null, "{}");
            ActivityEventDraft console = adminAdapter.toDraft(fromConsole).orElseThrow();
            assertEquals(ActivityTypes.USERS_ADMIN_CLIENT_ROLES_ADDED, console.type());
            assertEquals(ActorKind.PERSON, console.actor().kind());
            assertEquals("admin-id", console.actor().id());
            assertEquals(List.of("stock-read", "stock-write"), console.payload().get("roles"));

            assertEquals(ActivityTypes.USERS_ADMIN_PASSWORD_RESET, KeycloakAdminEventAdapter.type("ACTION", "USER", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21/reset-password"));
            assertEquals(ActivityTypes.USERS_ADMIN_SESSION_DELETED, KeycloakAdminEventAdapter.type("DELETE", "USER_SESSION", "sessions/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21"));
            assertEquals(ActivityTypes.USERS_ADMIN_OTHER, KeycloakAdminEventAdapter.type("UPDATE", "REALM", "realm"));
        }

        @Test
        void thePollerPagesNewestFirstUntilTheWatermarkAndIngestsOldestFirst() {
            FakeEventsClient client = new FakeEventsClient();
            long base = 1_700_000_000_000L;
            // Keycloak devuelve del mas nuevo al mas antiguo; la marca esta en base+2m, el solape vale 2m.
            for (int i = 9; i >= 0; i--) {
                client.login.add(new KeycloakEvent(base + i * 60_000L, "LOGIN", "r", "c", "u" + i, null, "10.0.0." + i, null, Map.of("username", "user" + i), null));
            }
            InboxMessageService inbox = mock(InboxMessageService.class);
            List<String> processed = new ArrayList<>();
            when(inbox.process(any(), any())).thenAnswer(invocation -> {
                processed.add(invocation.<InboxMessageCommand>getArgument(0).aggregateId());
                return InboxProcessingResult.PROCESSED;
            });
            SourceCursorService cursors = mock(SourceCursorService.class);
            SourceCursorService.Lease lease = new SourceCursorService.Lease(SourceKind.KEYCLOAK_LOGIN, "me", Instant.ofEpochMilli(base + 4 * 60_000L), Instant.now());
            when(cursors.acquire(SourceKind.KEYCLOAK_LOGIN)).thenReturn(Optional.of(lease));
            KeycloakProperties props = new KeycloakProperties("http://kc", "mto", "reg", null,
                    new KeycloakProperties.Events(true, true, false, null, null, 3, 10, Duration.ofMinutes(2), null, null, null), null);
            KeycloakEventsPollerImpl poller = new KeycloakEventsPollerImpl(client, loginAdapter, adminAdapter, inbox, mock(ActivityIngestor.class), cursors, props);

            KeycloakEventsPoller.PollSummary summary = poller.poll(SourceKind.KEYCLOAK_LOGIN);

            // since = base+4m - 2m = base+2m: entran los eventos 2..9 (8), en paginas de 3 → 3 paginas.
            assertEquals(8, summary.fetched());
            assertEquals(8, summary.ingested());
            assertEquals(List.of("u2", "u3", "u4", "u5", "u6", "u7", "u8", "u9"), processed, "del mas antiguo al mas nuevo");
            assertEquals(3, client.loginCalls.get());
            verify(cursors).release(eq(lease), eq(Instant.ofEpochMilli(base + 9 * 60_000L)), eq(null));
        }

        @Test
        void aPoisonedEventStaysFailedAndDoesNotBlockTheWatermark() {
            FakeEventsClient client = new FakeEventsClient();
            // Sin marca, el lector mira una hora atras: los eventos tienen que ser recientes.
            long base = Instant.now().toEpochMilli() - 10_000;
            client.login.add(new KeycloakEvent(base + 2000, "LOGIN", "r", "c", "good", null, null, null, Map.of("username", "good"), null));
            client.login.add(new KeycloakEvent(base + 1000, "LOGIN", "r", "c", "bad", null, null, null, Map.of("username", "bad"), null));
            InboxMessageService inbox = mock(InboxMessageService.class);
            when(inbox.process(any(), any())).thenAnswer(invocation -> {
                if ("bad".equals(invocation.<InboxMessageCommand>getArgument(0).aggregateId())) {
                    throw new IllegalStateException("poison");
                }
                return InboxProcessingResult.PROCESSED;
            });
            SourceCursorService cursors = mock(SourceCursorService.class);
            SourceCursorService.Lease lease = new SourceCursorService.Lease(SourceKind.KEYCLOAK_LOGIN, "me", null, null);
            when(cursors.acquire(SourceKind.KEYCLOAK_LOGIN)).thenReturn(Optional.of(lease));
            KeycloakEventsPollerImpl poller = new KeycloakEventsPollerImpl(client, loginAdapter, adminAdapter, inbox, mock(ActivityIngestor.class), cursors, keycloakProperties());

            KeycloakEventsPoller.PollSummary summary = poller.poll(SourceKind.KEYCLOAK_LOGIN);

            assertEquals(1, summary.ingested());
            assertEquals(1, summary.failed());
            verify(inbox).recordFailure(any(), any(IllegalStateException.class));
            verify(cursors).release(eq(lease), eq(Instant.ofEpochMilli(base + 2000)), eq(null));
        }

        @Test
        void aSourceFailureReleasesTheLeaseWithTheErrorAndALeasedSourceIsSkipped() {
            KeycloakEventsClient failing = mock(KeycloakEventsClient.class);
            when(failing.loginEvents(anyInt(), anyInt())).thenThrow(new DirectoryUnavailableException("Keycloak is down"));
            SourceCursorService cursors = mock(SourceCursorService.class);
            SourceCursorService.Lease lease = new SourceCursorService.Lease(SourceKind.KEYCLOAK_LOGIN, "me", null, Instant.now());
            when(cursors.acquire(SourceKind.KEYCLOAK_LOGIN)).thenReturn(Optional.of(lease));
            when(cursors.acquire(SourceKind.KEYCLOAK_ADMIN)).thenReturn(Optional.empty());
            KeycloakEventsPollerImpl poller = new KeycloakEventsPollerImpl(failing, loginAdapter, adminAdapter, mock(InboxMessageService.class),
                    mock(ActivityIngestor.class), cursors, keycloakProperties());

            Map<SourceKind, KeycloakEventsPoller.PollSummary> summaries = poller.pollOnce();

            assertNotNull(summaries.get(SourceKind.KEYCLOAK_LOGIN).error());
            verify(cursors).release(eq(lease), eq(null), anyString());
            assertFalse(summaries.get(SourceKind.KEYCLOAK_ADMIN).leased());
        }

        private final class FakeEventsClient implements KeycloakEventsClient {
            private final List<KeycloakEvent> login = new ArrayList<>();
            private final AtomicInteger loginCalls = new AtomicInteger();

            @Override
            public List<KeycloakEvent> loginEvents(int first, int max) {
                loginCalls.incrementAndGet();
                return login.subList(Math.min(first, login.size()), Math.min(first + max, login.size()));
            }

            @Override
            public List<KeycloakAdminEvent> adminEvents(int first, int max) {
                return List.of();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Entregas y audiencias
    // ---------------------------------------------------------------------------------------

    @Nested
    class Deliveries {

        private final DeliveryRelayService relay = mock(DeliveryRelayService.class);
        private final AudienceResolver resolver = mock(AudienceResolver.class);
        private final DeliveryChannel email = mock(DeliveryChannel.class);
        private final Notification notification = Notification.builder().ruleKey("r").category(ActivityCategory.ACCESS)
                .severity(ActivitySeverity.CRITICAL).title("t").build();

        private DeliveryDispatcherImpl dispatcher() {
            when(email.channel()).thenReturn("email");
            return new DeliveryDispatcherImpl(relay, resolver, List.of(email), new DeliveryRetryPolicy(properties()), properties());
        }

        private Delivery delivery(DeliveryScope scope, int attempts) {
            Delivery delivery = Delivery.builder().notificationId(UUID.randomUUID()).channel("email").scope(scope)
                    .audienceKind("PROFILE").audienceKey(scope == DeliveryScope.AUDIENCE ? "PROFILE:mto-ops" : null)
                    .recipient(scope == DeliveryScope.RECIPIENT ? "ops@mto.local" : null).recipientUsername("ops")
                    .status(DeliveryStatus.IN_PROGRESS).attempts(attempts).maxAttempts(3).nextAttemptAt(Instant.now()).build();
            ReflectionTestUtils.setField(delivery, "id", UUID.randomUUID());
            return delivery;
        }

        @Test
        void anAudienceIsExpandedAndItsRecipientsSentInTheSameBatch() throws Exception {
            Delivery audience = delivery(DeliveryScope.AUDIENCE, 1);
            Delivery recipient = delivery(DeliveryScope.RECIPIENT, 1);
            when(relay.claimDue(anyInt())).thenReturn(List.of(audience), List.of(recipient), List.of());
            when(relay.loadNotification(any())).thenReturn(Optional.of(notification));
            when(resolver.resolve(Audience.profile("mto-ops"))).thenReturn(List.of(new Recipient("ops", "ops@mto.local")));
            when(relay.isOverHourlyLimit(any(), any(), anyInt())).thenReturn(false);

            DeliveryDispatcher.DispatchSummary summary = dispatcher().dispatchDue();

            assertEquals(2, summary.claimed());
            assertEquals(1, summary.expanded());
            assertEquals(1, summary.sent());
            verify(relay).expand(eq(audience), eq(List.of(new Recipient("ops", "ops@mto.local"))));
            verify(email).send(eq(notification), eq(new Recipient("ops", "ops@mto.local")));
            verify(relay).markSent(recipient);
        }

        @Test
        void aChannelFailureIsRescheduledUntilTheAttemptsRunOut() throws Exception {
            Delivery first = delivery(DeliveryScope.RECIPIENT, 1);
            Delivery last = delivery(DeliveryScope.RECIPIENT, 3);
            when(relay.claimDue(anyInt())).thenReturn(List.of(first, last), List.of());
            when(relay.loadNotification(any())).thenReturn(Optional.of(notification));
            org.mockito.Mockito.doThrow(new IllegalStateException("smtp down")).when(email).send(any(), any());

            DeliveryDispatcher.DispatchSummary summary = dispatcher().dispatchDue();

            assertEquals(1, summary.rescheduled());
            assertEquals(1, summary.dead());
            verify(relay).reschedule(eq(first), any(), anyString());
            verify(relay).markDead(eq(last), anyString());
        }

        @Test
        void aDirectoryOutageOnlyDelaysTheEmailAndAMissingChannelSkipsIt() {
            Delivery audience = delivery(DeliveryScope.AUDIENCE, 1);
            when(relay.claimDue(anyInt())).thenReturn(List.of(audience), List.of());
            when(relay.loadNotification(any())).thenReturn(Optional.of(notification));
            when(resolver.resolve(any())).thenThrow(new DirectoryUnavailableException("keycloak down"));

            assertEquals(1, dispatcher().dispatchDue().rescheduled());

            Delivery push = delivery(DeliveryScope.RECIPIENT, 1);
            push.setChannel("push");
            when(relay.claimDue(anyInt())).thenReturn(List.of(push), List.of());
            assertEquals(1, dispatcher().dispatchDue().skipped());
            verify(relay).markSkipped(eq(push), anyString());
        }

        @Test
        void theHourlyLimitSkipsTheEmailInsteadOfSendingIt() {
            Delivery recipient = delivery(DeliveryScope.RECIPIENT, 1);
            when(relay.claimDue(anyInt())).thenReturn(List.of(recipient), List.of());
            when(relay.loadNotification(any())).thenReturn(Optional.of(notification));
            when(relay.isOverHourlyLimit("email", "ops@mto.local", 20)).thenReturn(true);

            assertEquals(1, dispatcher().dispatchDue().skipped());
        }

        @Test
        void theRetryPolicyGrowsAndCaps() {
            DeliveryRetryPolicy policy = new DeliveryRetryPolicy(properties());
            Instant now = Instant.now();
            Duration first = Duration.between(now, policy.nextAttemptAt(1, now));
            Duration fifth = Duration.between(now, policy.nextAttemptAt(5, now));
            Duration huge = Duration.between(now, policy.nextAttemptAt(40, now));
            assertTrue(first.compareTo(Duration.ofSeconds(30)) >= 0 && first.compareTo(Duration.ofSeconds(38)) <= 0, first.toString());
            assertTrue(fifth.compareTo(first) > 0);
            assertTrue(huge.compareTo(Duration.ofHours(1).plus(Duration.ofMinutes(15))) <= 0);
        }

        @Test
        void aClientRoleAudienceIncludesTheMembersOfTheProfilesThatGrantIt() {
            KeycloakDirectoryClient directory = mock(KeycloakDirectoryClient.class);
            when(directory.findClient("mto-stock-api")).thenReturn(Optional.of(new KeycloakClient("c-uuid", "mto-stock-api")));
            when(directory.clientRoleMembers(eq("c-uuid"), eq("stock-read"), anyInt(), anyInt()))
                    .thenReturn(List.of(new KeycloakUser("1", "direct", "direct@mto.local", true, null, null)));
            when(directory.realmRoles(anyInt(), anyInt())).thenReturn(List.of(
                    new KeycloakRole("r1", "mto-warehouse-admin", true, false, null),
                    new KeycloakRole("r2", "offline_access", false, false, null),
                    new KeycloakRole("r3", "mto-viewer", true, false, null)));
            when(directory.clientCompositesOfRealmRole("mto-warehouse-admin", "c-uuid"))
                    .thenReturn(List.of(new KeycloakRole("x", "stock-read", false, true, "c-uuid")));
            when(directory.clientCompositesOfRealmRole("mto-viewer", "c-uuid")).thenReturn(List.of());
            when(directory.realmRoleMembers(eq("mto-warehouse-admin"), anyInt(), anyInt())).thenReturn(List.of(
                    new KeycloakUser("2", "almacen.responsable", "almacen@mto.local", true, null, null),
                    new KeycloakUser("3", "disabled", "d@mto.local", false, null, null),
                    new KeycloakUser("1", "direct", "direct@mto.local", true, null, null)));
            KeycloakDirectoryAudienceResolver audienceResolver = new KeycloakDirectoryAudienceResolver(directory, keycloakProperties());

            List<Recipient> recipients = audienceResolver.resolve(Audience.clientRole("mto-stock-api", "stock-read"));

            assertEquals(List.of(new Recipient("direct", "direct@mto.local"), new Recipient("almacen.responsable", "almacen@mto.local")), recipients);
            audienceResolver.resolve(Audience.clientRole("mto-stock-api", "stock-read"));
            verify(directory, times(1)).findClient("mto-stock-api");
        }

        @Test
        void aUserWithoutEmailIsARecipientWithoutAddressAndAnOutageIsNotCached() {
            KeycloakDirectoryClient directory = mock(KeycloakDirectoryClient.class);
            when(directory.findUserByUsername("alice")).thenReturn(Optional.of(new KeycloakUser("1", "alice", null, true, null, null)));
            when(directory.realmRoleMembers(eq("mto-ops"), anyInt(), anyInt())).thenThrow(new DirectoryUnavailableException("down"))
                    .thenReturn(List.of(new KeycloakUser("2", "ops", "ops@mto.local", true, null, null)));
            KeycloakDirectoryAudienceResolver audienceResolver = new KeycloakDirectoryAudienceResolver(directory, keycloakProperties());

            assertEquals(List.of(new Recipient("alice", null)), audienceResolver.resolve(Audience.user("alice")));
            assertThrows(DirectoryUnavailableException.class, () -> audienceResolver.resolve(Audience.profile("mto-ops")));
            assertEquals(1, audienceResolver.resolve(Audience.profile("mto-ops")).size());
        }
    }
}
