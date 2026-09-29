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
import com.alejandro.mtonotification.domain.model.Subject;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Los servicios con dobles: el motor de reglas, la ingesta, los adaptadores de Keycloak, de
 * datos maestros, de los trabajos de configuracion, de mto-users y de mto-maintenance (estos tres
 * con los ejemplos que cada productor versiona; el ultimo, ademas, con sus reglas reales pasando
 * por el motor), el detector de rachas, el correlador de usuarios, el despachador y el
 * resolutor de audiencias. Lo que decide la base de datos (idempotencia, rafagas, frenos, la
 * fusion en si) se prueba contra PostgreSQL en los {@code *DataJpaTest}.
 */
class BusinessLayerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String USER_ID = "8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21";

    /** Un ejemplo de {@code src/test/resources/contracts}, copiado del repositorio del productor, leido como lo lee el consumidor. */
    static SourceEnvelope fixture(String path) {
        try {
            return JSON.readValue(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8), SourceEnvelope.class);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    static ActivityEventDraft ingested(ActivityIngestor ingestor) {
        ArgumentCaptor<ActivityEventDraft> captor = ArgumentCaptor.forClass(ActivityEventDraft.class);
        verify(ingestor).ingest(captor.capture());
        return captor.getValue();
    }

    static ActivityEvent line(String source, String type, Actor actor, String subjectType, String subjectId, Instant at) {
        ActivityEvent event = new TestEvent();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        event.setSourceService(source);
        event.setSourceEventId(UUID.randomUUID().toString());
        event.setType(type);
        event.setCategory(ActivityCategory.ofType(type));
        event.setSeverity(ActivitySeverity.INFO);
        event.setOccurredAt(at);
        event.setRecordedAt(at);
        event.setActorKind(actor.kind());
        event.setActorUsername(actor.username());
        event.setActorId(actor.id());
        event.setSubjectType(subjectType);
        event.setSubjectId(subjectId);
        event.setEventCount(1);
        event.setPayload("{}");
        return event;
    }

    static NotificationProperties properties() {
        return new NotificationProperties("classpath:notification-rules.yml", null, null, null, null, null, null, null, null);
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
        private final KeycloakDirectoryClient directory = mock(KeycloakDirectoryClient.class);
        private final KeycloakAdminEventAdapter adminAdapter = new KeycloakAdminEventAdapter(keycloakProperties(), JSON, directory);

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
            // authDetails.clientId es el id interno del cliente, no su clientId: el directorio lo traduce.
            String usersSvcInternalId = "0f0f0f0f-1111-4222-8333-444444444444";
            String masterConsoleInternalId = "9a9a9a9a-1111-4222-8333-444444444444";
            when(directory.findClientById(usersSvcInternalId)).thenReturn(Optional.of(new KeycloakClient(usersSvcInternalId, "mto-users-svc")));
            when(directory.findClientById(masterConsoleInternalId)).thenReturn(Optional.empty());

            KeycloakAdminEvent fromApp = new KeycloakAdminEvent(1L, "realm", new KeycloakAdminEvent.AuthDetails("realm", usersSvcInternalId, "svc", "10.0.0.2"),
                    "UPDATE", "USER", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", null, null, "{}");
            ActivityEventDraft app = adminAdapter.toDraft(fromApp).orElseThrow();
            assertEquals(ActivityTypes.USERS_ADMIN_USER_UPDATED, app.type());
            assertEquals(ActorKind.SERVICE, app.actor().kind());
            assertEquals("service-account-mto-users-svc", app.actor().username());
            assertEquals("8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", app.subject().id());
            assertEquals("mto-users-svc", app.payload().get("clientId"));
            assertEquals("realm", app.payload().get("authRealmId"));
            assertNull(app.ipAddress(), "la IP no vive fuera de ACCESS");

            // La consola de master (o kcadm): un cliente que no esta en el realm se queda con su UUID y es una persona.
            KeycloakAdminEvent fromConsole = new KeycloakAdminEvent(1L, "realm", new KeycloakAdminEvent.AuthDetails("master", masterConsoleInternalId, "admin-id", null),
                    "CREATE", "CLIENT_ROLE_MAPPING", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21/role-mappings/clients/abc",
                    "[{\"id\":\"r1\",\"name\":\"stock-read\"},{\"id\":\"r2\",\"name\":\"stock-write\"}]", null, "{}");
            ActivityEventDraft console = adminAdapter.toDraft(fromConsole).orElseThrow();
            assertEquals(ActivityTypes.USERS_ADMIN_CLIENT_ROLES_ADDED, console.type());
            assertEquals(ActorKind.PERSON, console.actor().kind());
            assertEquals("admin-id", console.actor().id());
            assertEquals(masterConsoleInternalId, console.payload().get("clientId"));
            assertEquals("master", console.payload().get("authRealmId"));
            assertEquals(List.of("stock-read", "stock-write"), console.payload().get("roles"));

            // El directorio se pregunta una vez por cliente.
            adminAdapter.toDraft(fromApp);
            adminAdapter.toDraft(fromConsole);
            verify(directory, times(1)).findClientById(usersSvcInternalId);
            verify(directory, times(1)).findClientById(masterConsoleInternalId);

            assertEquals(ActivityTypes.USERS_ADMIN_PASSWORD_RESET, KeycloakAdminEventAdapter.type("ACTION", "USER", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21/reset-password"));
            assertEquals(ActivityTypes.USERS_ADMIN_SESSION_DELETED, KeycloakAdminEventAdapter.type("DELETE", "USER_SESSION", "sessions/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21"));
            assertEquals(ActivityTypes.USERS_ADMIN_OTHER, KeycloakAdminEventAdapter.type("UPDATE", "REALM", "realm"));
        }

        @Test
        void aDirectoryOutageWhileResolvingTheClientLeavesTheEventForTheNextPass() {
            when(directory.findClientById(anyString())).thenThrow(new DirectoryUnavailableException("Keycloak is down"));
            KeycloakAdminEvent event = new KeycloakAdminEvent(1L, "realm", new KeycloakAdminEvent.AuthDetails("realm", "1b1b1b1b-1111-4222-8333-444444444444", "svc", null),
                    "UPDATE", "USER", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", null, null, "{}");

            assertThrows(DirectoryUnavailableException.class, () -> adminAdapter.toDraft(event));
            KeycloakAdminEvent withoutClient = new KeycloakAdminEvent(1L, "realm", new KeycloakAdminEvent.AuthDetails("realm", null, "svc", null),
                    "UPDATE", "USER", "users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", null, null, "{}");
            assertNull(adminAdapter.toDraft(withoutClient).orElseThrow().payload().get("clientId"));
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
        void aUserIdAudienceIsResolvedByTheDirectoryAndADisabledAccountGetsNothing() {
            KeycloakDirectoryClient directory = mock(KeycloakDirectoryClient.class);
            when(directory.findUserById("u-9")).thenReturn(Optional.of(new KeycloakUser("u-9", "carol", "carol@mto.local", true, null, null)));
            when(directory.findUserById("u-off")).thenReturn(Optional.of(new KeycloakUser("u-off", "off", "off@mto.local", false, null, null)));
            KeycloakDirectoryAudienceResolver audienceResolver = new KeycloakDirectoryAudienceResolver(directory, keycloakProperties());

            assertEquals(List.of(new Recipient("carol", "carol@mto.local")), audienceResolver.resolve(Audience.userId("u-9")));
            assertTrue(audienceResolver.resolve(Audience.userId("u-off")).isEmpty());
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

    // ---------------------------------------------------------------------------------------
    // Trabajos de mto-configuration
    // ---------------------------------------------------------------------------------------

    @Nested
    class ConfigurationJobs {

        private final ActivityIngestor ingestor = mock(ActivityIngestor.class);
        private final ConfigurationSourceAdapter adapter = new ConfigurationSourceAdapter(ingestor);
        private final SourceEventContext context = new SourceEventContext("configuration", null, "mto.configuration.job.finished");

        @Test
        void theJobFinishedExampleOfConfigurationBecomesOneLineForWhoLaunchedIt() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(fixture("contracts/mto-configuration/job-finished.json"), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivityTypes.CONFIGURATION_JOB_FINISHED, draft.type());
            assertEquals(ActivitySeverity.WARNING, draft.severity(), "COMPLETED_WITH_ERRORS es un aviso, no una noticia");
            assertEquals("mto-configuration", draft.sourceService());
            assertEquals("b2c9e4d1-5a6f-4e7b-8c9d-0a1b2c3d4e5f", draft.sourceEventId());
            assertEquals(Instant.parse("2026-09-29T07:00:09Z"), draft.occurredAt(), "cuando termino, no cuando se escribio el sobre");
            assertEquals(Actor.person("config.responsable", "6f1b1c8e-0000-4000-8000-000000000002"), draft.actor());
            assertEquals(Subject.of("job", "00000000-0000-4000-8000-0000000000aa", "LOV_IMPORT"), draft.subject());
            assertEquals("00000000-0000-4000-8000-0000000000aa", draft.correlationId());
            assertEquals("COMPLETED_WITH_ERRORS", draft.payload().get("status"));
            assertEquals(118, draft.payload().get("successfulItems"));
            assertEquals(2, draft.payload().get("failedItems"));
            assertEquals("config.responsable", draft.payload().get("createdBy"));
            assertFalse(draft.payload().containsKey("trackId"), "lo nulo no se guarda");
        }

        @Test
        void aCompletedJobIsInfoAndWithoutActorInTheEnvelopeTheLauncherIsTheActor() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());
            SourceEnvelope envelope = new SourceEnvelope(UUID.randomUUID(), "job-j-1", "mto-configuration", Instant.parse("2026-09-28T10:00:00Z"),
                    "CONFIGURATION_JOB_FINISHED", Map.of("entityName", "job", "entityId", "j-1", "eventName", "finished",
                            "values", Map.of("jobId", "j-1", "type", "PROFILE_IMPORT", "status", "COMPLETED", "createdBy", "config.editor",
                                    "finishedAt", "not-a-date", "totalItems", 3)), "hash", null, null);

            adapter.handle(envelope, context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivitySeverity.INFO, draft.severity());
            assertEquals(Actor.person("config.editor", null), draft.actor());
            assertEquals(Instant.parse("2026-09-28T10:00:00Z"), draft.occurredAt(), "una fecha ilegible cae en la del sobre");
            assertEquals("j-1", draft.correlationId(), "sin correlacion en el sobre, el trabajo es la correlacion");
        }

        @Test
        void anEventOfConfigurationWithoutAdapterIsRecordedWithItsTypeAndWithoutValuesAndAnIncompleteOneIsPermanent() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());
            SourceEnvelope unknown = new SourceEnvelope(UUID.randomUUID(), "x", "mto-configuration", Instant.now(), "CONFIGURATION_TRACK_RENUMBERED",
                    Map.of("entityName", "track", "entityId", "7", "eventName", "Renumbered", "values", Map.of("secretPlan", "x", "code", "V7")),
                    "hash", new SourceActor("u1", "config.editor", "PERSON"), "req-1");

            adapter.handle(unknown, context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals("configuration.track.renumbered", draft.type());
            assertEquals(Map.of("entityName", "track", "entityId", "7", "eventName", "renumbered"), draft.payload(), "sin valores: nadie los ha revisado");
            assertEquals(Subject.of("track", "7"), draft.subject());

            SourceEnvelope nameless = new SourceEnvelope(UUID.randomUUID(), "x", "mto-configuration", Instant.now(), "X",
                    Map.of("entityName", "job", "values", Map.of()), null, null, null);
            assertThrows(UnprocessableSourceEventException.class, () -> adapter.handle(nameless, context));
        }
    }

    // ---------------------------------------------------------------------------------------
    // mto-users: sus acciones y el correlador con los eventos de Keycloak
    // ---------------------------------------------------------------------------------------

    @Nested
    class Users {

        private final ActivityIngestor ingestor = mock(ActivityIngestor.class);
        private final UsersSourceAdapter adapter = new UsersSourceAdapter(ingestor);
        private final SourceEventContext context = new SourceEventContext("users", null, "mto.users.user.created");

        @Test
        void theUserCreatedExampleOfUsersBecomesALineAboutTheTargetUserWithThePersonWhoDidIt() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(fixture("contracts/mto-users/user-created.json"), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivityTypes.USERS_USER_CREATED, draft.type());
            assertEquals(ActivitySeverity.INFO, draft.severity());
            assertEquals("mto-users", draft.sourceService());
            assertEquals("7c2e6a10-1b2c-4d3e-8f90-0a1b2c3d4e5f", draft.sourceEventId());
            assertEquals(Instant.parse("2026-09-29T09:00:00Z"), draft.occurredAt());
            assertEquals(Actor.person("usuarios.responsable", "6f1b1c8e-0000-4000-8000-000000000002"), draft.actor());
            assertEquals(Subject.of("user", "2f1c9d1e-0000-4000-8000-000000000001", "ana.nueva"), draft.subject());
            assertEquals("8c3b8c1a-1111-4222-8333-444444444444", draft.correlationId());
            assertEquals("ana.nueva", draft.payload().get("targetUsername"));
            assertEquals(true, draft.payload().get("enabled"));
            assertEquals(true, draft.payload().get("temporaryAccess"), "lo que mto-users llama temporaryCredential, con un nombre que el saneador deja pasar");
            assertEquals(List.of("UPDATE_PASSWORD"), draft.payload().get("requiredActions"));
            assertFalse(draft.payload().containsKey("temporaryCredential"));
        }

        @Test
        void theProfileAssignedExampleKnowsTheUserOnlyByIdAndKeepsTheProfile() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(fixture("contracts/mto-users/profile-assigned.json"), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivityTypes.USERS_PROFILE_ASSIGNED, draft.type());
            assertEquals(Subject.of("user", "2f1c9d1e-0000-4000-8000-000000000001", null), draft.subject());
            assertEquals("mto-users-viewer", draft.payload().get("profile"));
            assertFalse(draft.payload().containsKey("targetUsername"), "mto-users no lo sabe: la regla avisa por USER_ID");
            assertEquals("2f1c9d1e-0000-4000-8000-000000000001", draft.payload().get("targetUserId"));
        }

        @Test
        void takingSomebodyOutIsWarningsWithTheSessionAndTheReservedNamespaceIsRefused() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(usersEnvelope("session", "revoked", Map.of("session", "sess-1")), context);
            adapter.handle(usersEnvelope("user", "deleted", Map.of("targetUsername", "ana.baja")), context);
            adapter.handle(usersEnvelope("client-roles", "added", Map.of("client", "mto-stock-api", "roles", List.of("stock-read"))), context);

            ArgumentCaptor<ActivityEventDraft> drafts = ArgumentCaptor.forClass(ActivityEventDraft.class);
            verify(ingestor, times(3)).ingest(drafts.capture());
            ActivityEventDraft session = drafts.getAllValues().get(0);
            assertEquals(ActivityTypes.USERS_SESSION_REVOKED, session.type());
            assertEquals(ActivitySeverity.WARNING, session.severity());
            assertEquals("sess-1", session.payload().get("session"), "la sesion es lo que casa con la linea de Keycloak");
            assertEquals(ActivitySeverity.WARNING, drafts.getAllValues().get(1).severity());
            assertEquals("ana.baja", drafts.getAllValues().get(1).subject().label());
            assertEquals(ActivityTypes.USERS_CLIENT_ROLES_ADDED, drafts.getAllValues().get(2).type());
            assertEquals(ActivitySeverity.INFO, drafts.getAllValues().get(2).severity());
            assertEquals(List.of("stock-read"), drafts.getAllValues().get(2).payload().get("roles"));

            assertThrows(UnprocessableSourceEventException.class,
                    () -> adapter.handle(usersEnvelope("admin", "user-updated", Map.of()), context));
        }

        private SourceEnvelope usersEnvelope(String entity, String event, Map<String, Object> detail) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            values.put("targetUserId", USER_ID);
            values.putAll(detail);
            return new SourceEnvelope(UUID.randomUUID(), "user-" + USER_ID, "mto-users", Instant.parse("2026-09-28T10:00:00Z"),
                    "USERS_" + entity.toUpperCase().replace('-', '_') + "_" + event.toUpperCase().replace('-', '_'),
                    Map.of("entityName", entity, "entityId", USER_ID, "eventName", event, "values", values), "hash",
                    new SourceActor("u-admin", "usuarios.responsable", "PERSON"), "req-1");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fuente mto-maintenance
    // ---------------------------------------------------------------------------------------

    @Nested
    class Maintenance {

        private final ActivityIngestor ingestor = mock(ActivityIngestor.class);
        private final MaintenanceSourceAdapter adapter = new MaintenanceSourceAdapter(ingestor);
        private final SourceEventContext context = new SourceEventContext("maintenance", null, "mto.maintenance.order.created");

        @Test
        void theUrgentOrderExampleBecomesACriticalLineAboutTheOrderWithThePersonWhoOpenedIt() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(fixture("contracts/mto-maintenance/order-created.json"), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivityTypes.MAINTENANCE_ORDER_CREATED, draft.type());
            assertEquals(ActivitySeverity.CRITICAL, draft.severity(), "una orden URGENT");
            assertEquals("mto-maintenance", draft.sourceService());
            assertEquals("a0000000-0000-4000-8000-000000000001", draft.sourceEventId());
            assertEquals(Instant.parse("2026-09-29T09:00:00Z"), draft.occurredAt());
            assertEquals(Actor.person("mantenimiento.tecnico", "6f1b1c8e-0000-4000-8000-000000000031"), draft.actor());
            assertEquals(Subject.of("order", "40000000-0000-4000-8000-000000000002", "MO-000124"), draft.subject());
            assertEquals("8c3b8c1a-1111-4222-8333-444444444444", draft.correlationId(), "el X-Correlation-Id de la peticion");
            assertEquals("order", draft.payload().get("entityName"));
            assertEquals("created", draft.payload().get("eventName"));
            assertEquals("URGENT", draft.payload().get("type"));
            assertEquals("PRF-12-2.27", draft.payload().get("assetCode"));
            assertEquals(6, ((Number) draft.payload().get("executionPackageId")).intValue());
            assertFalse(draft.payload().containsKey("stationId"), "un valor nulo no se guarda");
        }

        @Test
        void theMaterialRejectedExampleIsAWarningWithTheStockCodeAndTheOrderInItsLabel() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(fixture("contracts/mto-maintenance/material-rejected.json"), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivityTypes.MAINTENANCE_MATERIAL_REJECTED, draft.type());
            assertEquals(ActivitySeverity.WARNING, draft.severity());
            assertEquals(Subject.of("material", "a1000000-0000-4000-8000-000000000001", "MO-000123 GA70"), draft.subject());
            assertEquals("STK-001", draft.payload().get("stockErrorCode"), "lo que distingue la falta de existencias de otro rechazo");
            assertEquals("reserve", draft.payload().get("step"));
            assertEquals(409, ((Number) draft.payload().get("stockHttpStatus")).intValue());
        }

        @Test
        void thePreventiveDueSoonExampleComesFromTheSystemWithTheDateAsSubject() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(fixture("contracts/mto-maintenance/preventive-due-soon.json"), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals(ActivityTypes.MAINTENANCE_PREVENTIVE_DUE_SOON, draft.type());
            assertEquals(ActivitySeverity.WARNING, draft.severity(), "uno ya vencido");
            assertEquals(Actor.system(), draft.actor(), "el trabajo diario no tiene persona detras");
            assertNull(draft.correlationId());
            assertEquals("d1732d1f-eb16-3e1a-8151-6406237ab658", draft.sourceEventId(), "sale de la fecha: el segundo del dia es un duplicado en el inbox");
            assertEquals(Subject.of("preventive", "2026-09-29", "2 due"), draft.subject());
            assertEquals(2, ((List<?>) draft.payload().get("assets")).size());
        }

        @Test
        void everyExampleOfTheProducerIsRecordedWithTheSeverityOfTheFact() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());
            Map<String, ActivitySeverity> expected = new java.util.LinkedHashMap<>();
            expected.put("order-created", ActivitySeverity.CRITICAL);
            expected.put("order-status-changed", ActivitySeverity.INFO);
            expected.put("order-reassigned", ActivitySeverity.INFO);
            expected.put("defect-created", ActivitySeverity.WARNING);
            expected.put("defect-status-changed", ActivitySeverity.INFO);
            expected.put("inspection-created", ActivitySeverity.WARNING);
            expected.put("inspection-item-failed", ActivitySeverity.WARNING);
            expected.put("inspection-defect-created", ActivitySeverity.WARNING);
            expected.put("inspection-corrective-order-created", ActivitySeverity.WARNING);
            expected.put("shift-started", ActivitySeverity.INFO);
            expected.put("shift-closed", ActivitySeverity.INFO);
            expected.put("material-rejected", ActivitySeverity.WARNING);
            expected.put("material-failed", ActivitySeverity.WARNING);
            expected.put("material-in-doubt", ActivitySeverity.WARNING);
            expected.put("asset-disabled", ActivitySeverity.WARNING);
            expected.put("preventive-due-soon", ActivitySeverity.WARNING);

            expected.keySet().forEach(name -> adapter.handle(fixture("contracts/mto-maintenance/" + name + ".json"), context));

            ArgumentCaptor<ActivityEventDraft> drafts = ArgumentCaptor.forClass(ActivityEventDraft.class);
            verify(ingestor, times(expected.size())).ingest(drafts.capture());
            List<ActivityEventDraft> all = drafts.getAllValues();
            int index = 0;
            for (Map.Entry<String, ActivitySeverity> entry : expected.entrySet()) {
                ActivityEventDraft draft = all.get(index++);
                assertEquals("maintenance." + entry.getKey().replaceFirst("-", "."), draft.type(), entry.getKey());
                assertTrue(ActivityTypes.isKnown(draft.type()), "los dieciseis estan en el catalogo: " + draft.type());
                assertEquals(entry.getValue(), draft.severity(), entry.getKey());
                assertEquals("mto-maintenance", draft.sourceService());
                assertNotNull(draft.subject().id(), entry.getKey());
                assertEquals(entry.getKey().replaceFirst("-.*", ""), draft.payload().get("entityName"), entry.getKey());
            }
            assertEquals(Actor.system(), all.get(13).actor(), "el reintento automatico de stock no tiene persona");
            assertEquals(Actor.person("mantenimiento.responsable", "6f1b1c8e-0000-4000-8000-000000000032"), all.get(14).actor());
        }

        @Test
        void anEventTheCatalogueDoesNotKnowIsRecordedAnywayAndOneWithoutEntityIsRefused() {
            when(ingestor.ingest(any())).thenReturn(Optional.empty());

            adapter.handle(maintenanceEnvelope(Map.of("entityName", "task", "entityId", "t-1", "eventName", "completed",
                    "values", Map.of("code", "MO-000123", "minutes", 45))), context);

            ActivityEventDraft draft = ingested(ingestor);
            assertEquals("maintenance.task.completed", draft.type());
            assertFalse(ActivityTypes.isKnown(draft.type()), "un evento nuevo del productor se registra antes de tener regla");
            assertEquals(ActivitySeverity.INFO, draft.severity());
            assertEquals(Subject.of("task", "t-1", "MO-000123"), draft.subject());
            assertEquals(45, draft.payload().get("minutes"));

            assertThrows(UnprocessableSourceEventException.class,
                    () -> adapter.handle(maintenanceEnvelope(Map.of("entityId", "t-1", "eventName", "completed")), context));
        }

        @Test
        void theShippedMaintenanceRulesTellTheManagerAndTheAssignedPerson() {
            List<NotificationFactory.NotificationDraft> created = new ArrayList<>();
            NotificationFactory factory = draft -> {
                created.add(draft);
                return Notification.builder().build();
            };
            ThrottleGate throttle = mock(ThrottleGate.class);
            when(throttle.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
            RuleEngineImpl engine = new RuleEngineImpl(new YamlRuleRepository(new ClassPathResource("notification-rules.yml"), Map.of()),
                    new RuleExpressionEvaluator(), throttle, factory);
            java.util.function.Function<String, NotificationFactory.NotificationDraft> only = name -> {
                created.clear();
                ActivityEventDraft draft = draftOf(name);
                engine.evaluate(lineOf(draft), draft.payload());
                assertEquals(1, created.size(), name + " dispara " + created.stream().map(NotificationFactory.NotificationDraft::ruleKey).toList());
                return created.getFirst();
            };

            NotificationFactory.NotificationDraft urgent = only.apply("order-created");
            assertEquals("maintenance-order-urgent", urgent.ruleKey());
            assertEquals(ActivitySeverity.CRITICAL, urgent.severity());
            assertEquals(List.of(Audience.profile("mto-maintenance-manager")), urgent.audiences());
            assertEquals(List.of("inbox", "email"), urgent.channels());
            assertEquals("Orden urgente MO-000124: Broken contact wire at kp 12+848", urgent.title());
            assertEquals("mantenimiento.tecnico ha abierto la orden urgente MO-000124 sobre PRF-12-2.27 (12-2.27), via 2.", urgent.body());
            assertEquals("/mantenimiento/ordenes/40000000-0000-4000-8000-000000000002", urgent.link());

            NotificationFactory.NotificationDraft assigned = only.apply("order-status-changed");
            assertEquals("maintenance-order-assigned", assigned.ruleKey());
            assertEquals(List.of(Audience.user("mantenimiento.tecnico")), assigned.audiences(), "a la persona asignada, no al responsable");
            assertEquals(List.of("inbox"), assigned.channels());
            assertEquals("Orden MO-000123 asignada a ti", assigned.title());
            assertEquals("/mantenimiento/ordenes/40000000-0000-4000-8000-000000000001", assigned.link());

            NotificationFactory.NotificationDraft noStock = only.apply("material-rejected");
            assertEquals("maintenance-material-no-stock", noStock.ruleKey(), "STK-001 es falta de existencias: tambien al almacen");
            assertEquals(List.of(Audience.profile("mto-maintenance-manager"), Audience.profile("mto-warehouse-admin")), noStock.audiences());
            assertEquals(ActivitySeverity.WARNING, noStock.severity(), "sin gravedad en la regla, la de la linea");
            assertEquals("Sin existencias de GA70 para la orden MO-000123", noStock.title());

            NotificationFactory.NotificationDraft inDoubt = only.apply("material-in-doubt");
            assertEquals("maintenance-material-stock-unavailable", inDoubt.ruleKey());
            verify(throttle).tryAcquire("maintenance-material-stock-unavailable", "40000000-0000-4000-8000-000000000001", Duration.ofHours(1));
            assertTrue(inDoubt.body().contains("la peticion RESERVATION queda en duda"), inDoubt.body());

            NotificationFactory.NotificationDraft dueSoon = only.apply("preventive-due-soon");
            assertEquals("maintenance-preventive-due-soon", dueSoon.ruleKey());
            assertEquals("2 preventivos vencen en 7 dias (1 ya vencidos)", dueSoon.title());
            assertTrue(dueSoon.body().contains("SEC-T2") && dueSoon.body().contains("PRF-12-2.27"), dueSoon.body());
            assertEquals(List.of("inbox", "email"), dueSoon.channels());
            assertEquals("/mantenimiento/activos", dueSoon.link());

            NotificationFactory.NotificationDraft shift = only.apply("shift-closed");
            assertEquals("maintenance-shift", shift.ruleKey());
            assertEquals("Turno SH-000077 cerrado", shift.title());
            assertTrue(shift.body().contains("330 minutos netos"), shift.body());

            for (String silent : List.of("defect-status-changed", "inspection-defect-created", "inspection-item-failed")) {
                created.clear();
                ActivityEventDraft draft = draftOf(silent);
                engine.evaluate(lineOf(draft), draft.payload());
                assertTrue(created.isEmpty(), silent + " se registra sin aviso");
            }
        }

        private ActivityEventDraft draftOf(String name) {
            ActivityIngestor recorder = mock(ActivityIngestor.class);
            when(recorder.ingest(any())).thenReturn(Optional.empty());
            new MaintenanceSourceAdapter(recorder).handle(fixture("contracts/mto-maintenance/" + name + ".json"), context);
            return ingested(recorder);
        }

        private ActivityEvent lineOf(ActivityEventDraft draft) {
            ActivityEvent event = line(draft.sourceService(), draft.type(), draft.actor(), draft.subject().type(), draft.subject().id(), draft.occurredAt());
            event.setSeverity(draft.severity());
            event.setSubjectLabel(draft.subject().label());
            event.setCorrelationId(draft.correlationId());
            return event;
        }

        private SourceEnvelope maintenanceEnvelope(Map<String, Object> data) {
            return new SourceEnvelope(UUID.randomUUID(), "ref", "mto-maintenance", Instant.parse("2026-09-29T09:00:00Z"), "MAINTENANCE_X",
                    data, "hash", new SourceActor("6f1b1c8e-0000-4000-8000-000000000031", "mantenimiento.tecnico", "PERSON"), "req-1");
        }
    }

    @Nested
    class UsersCorrelation {

        private final ActivityEventRepository repository = mock(ActivityEventRepository.class);
        private final UsersChangeCorrelator correlator = new UsersChangeCorrelator(repository, properties());
        private final Instant at = Instant.parse("2026-09-28T10:00:00Z");
        private final ActivityEventDraft anyDraft = ActivityEventDraft.builder().source("x", "y").type(ActivityTypes.USERS_USER_UPDATED)
                .occurredAt(at).build();

        @Test
        void theKeycloakLineArrivingAfterTheOneOfUsersIsSupersededByTheClosestOne() {
            ActivityEvent keycloak = line("keycloak-admin", ActivityTypes.USERS_ADMIN_USER_UPDATED, Actor.service("mto-users-svc", "svc"), "user", USER_ID, at);
            ActivityEvent near = line("mto-users", ActivityTypes.USERS_USER_DISABLED, Actor.person("alice", "u1"), "user", USER_ID, at.minusSeconds(5));
            ActivityEvent far = line("mto-users", ActivityTypes.USERS_USER_UPDATED, Actor.person("alice", "u1"), "user", USER_ID, at.minusSeconds(100));
            when(repository.findLinesBySubject(eq(Set.of(ActivityTypes.USERS_USER_UPDATED, ActivityTypes.USERS_USER_ENABLED, ActivityTypes.USERS_USER_DISABLED)),
                    eq("user"), eq(USER_ID), eq(at.minus(Duration.ofMinutes(2))), eq(at.plus(Duration.ofMinutes(2))))).thenReturn(List.of(far, near));
            when(repository.supersede(any(), any())).thenReturn(1);

            correlator.afterIngested(keycloak, anyDraft);

            verify(repository).supersede(keycloak.getId(), near.getId());
            verify(repository, never()).supersede(eq(keycloak.getId()), eq(far.getId()));
        }

        @Test
        void theUsersLineArrivingAfterSupersedesEveryOpenKeycloakLineOfThatChange() {
            ActivityEvent users = line("mto-users", ActivityTypes.USERS_OFFLINE_SESSION_ALL_REVOKED, Actor.person("alice", "u1"), "user", USER_ID, at);
            ActivityEvent consent1 = line("keycloak-admin", ActivityTypes.USERS_ADMIN_CONSENT_REVOKED, Actor.service("mto-users-svc", "svc"), "user", USER_ID, at.minusSeconds(3));
            ActivityEvent consent2 = line("keycloak-admin", ActivityTypes.USERS_ADMIN_CONSENT_REVOKED, Actor.service("mto-users-svc", "svc"), "user", USER_ID, at.minusSeconds(2));
            when(repository.findUnsupersededLines(eq("keycloak-admin"), eq(ActorKind.SERVICE), eq(Set.of(ActivityTypes.USERS_ADMIN_CONSENT_REVOKED)),
                    eq("user"), eq(USER_ID), any(), any())).thenReturn(List.of(consent1, consent2));
            when(repository.supersede(any(), any())).thenReturn(1);

            correlator.afterIngested(users, anyDraft);

            verify(repository).supersede(consent1.getId(), users.getId());
            verify(repository).supersede(consent2.getId(), users.getId());
        }

        @Test
        void aSessionClosedFromTheApplicationMatchesTheKeycloakLineOfThatSessionInEitherOrder() {
            ActivityEvent users = line("mto-users", ActivityTypes.USERS_SESSION_REVOKED, Actor.person("alice", "u1"), "user", USER_ID, at);
            ActivityEventDraft withSession = ActivityEventDraft.builder().source("mto-users", "s").type(ActivityTypes.USERS_SESSION_REVOKED)
                    .occurredAt(at).payload(Map.of("session", "sess-1")).build();
            ActivityEvent keycloak = line("keycloak-admin", ActivityTypes.USERS_ADMIN_SESSION_DELETED, Actor.service("mto-users-svc", "svc"), "session", "sess-1", at.plusSeconds(3));
            when(repository.findUnsupersededLines(eq("keycloak-admin"), eq(ActorKind.SERVICE), eq(Set.of(ActivityTypes.USERS_ADMIN_SESSION_DELETED)),
                    eq("session"), eq("sess-1"), any(), any())).thenReturn(List.of(keycloak));
            when(repository.findLinesBySessionPayload(eq(Set.of(ActivityTypes.USERS_SESSION_REVOKED, ActivityTypes.USERS_OFFLINE_SESSION_REVOKED)),
                    eq("sess-1"), any(), any())).thenReturn(List.of(users));
            when(repository.supersede(any(), any())).thenReturn(1);

            correlator.afterIngested(users, withSession);
            correlator.afterIngested(keycloak, anyDraft);

            verify(repository, times(2)).supersede(keycloak.getId(), users.getId());
        }

        @Test
        void aChangeMadeOutsideTheApplicationAndAnythingThatIsNotAUsersChangeIsNeverFused() {
            correlator.afterIngested(line("keycloak-admin", ActivityTypes.USERS_ADMIN_USER_UPDATED, new Actor(ActorKind.PERSON, null, "admin-id"), "user", USER_ID, at), anyDraft);
            correlator.afterIngested(line("keycloak-admin", ActivityTypes.USERS_ADMIN_OTHER, Actor.service("mto-users-svc", "svc"), "realm-resource", "realm", at), anyDraft);
            correlator.afterIngested(event(ActivityTypes.ACCESS_LOGIN, ActivityCategory.ACCESS, ActivitySeverity.INFO, "alice", null), anyDraft);

            verifyNoInteractions(repository);
        }
    }
}
