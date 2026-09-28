package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.dto.inbox.InboxFilter;
import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.inbox.ReadAllResponse;
import com.alejandro.mtonotification.application.dto.inbox.UnreadCountResponse;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.mapper.NotificationMapperImpl;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.DeliveryDispatchTrigger;
import com.alejandro.mtonotification.application.service.DeliveryRelayService;
import com.alejandro.mtonotification.application.service.InboxQueryService;
import com.alejandro.mtonotification.application.service.NotificationFactory;
import com.alejandro.mtonotification.configuration.JpaAuditingConfiguration;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.configuration.security.CurrentUserService;
import com.alejandro.mtonotification.configuration.security.JwtClaimNames;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La bandeja y las entregas contra PostgreSQL: a quien va una notificacion se resuelve al leer con
 * las claves del token, leida por persona, «todas leidas» en O(1), y las entregas con su reclamo,
 * su expansion y sus reintentos.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({JpaAuditingConfiguration.class, NotificationMapperImpl.class, CurrentUserService.class})
@TestPropertySource(properties = {
        "app.notification.rules-location=classpath:notification-rules.yml",
        "app.notification.inbox.unread-count-cap=2",
        "app.notification.delivery.max-attempts=3"
})
class NotificationInboxDataJpaTest extends PostgreSQLTestContainer {

    /** Los servicios son package-private tras sus interfaces: se recogen por nombre, con sus properties. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    @ComponentScan(basePackages = "com.alejandro.mtonotification.application.service.impl", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                    ".*\\.NotificationFactoryImpl", ".*\\.InboxQueryServiceImpl", ".*\\.DeliveryRelayServiceImpl"}))
    static class InboxServicesConfiguration {
    }

    private static final Audience OPS = Audience.profile("mto-ops");
    private static final Audience ALICE = Audience.user("alice");
    private static final Audience BOB = Audience.user("bob");

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationAudienceRepository audienceRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private NotificationFactory notificationFactory;

    @Autowired
    private InboxQueryService inboxQueryService;

    @Autowired
    private DeliveryRelayService relay;

    @MockitoBean
    private DeliveryDispatchTrigger dispatchTrigger;

    @MockitoBean
    private ActivityIngestor activityIngestor;

    @BeforeEach
    void signInAsAlice() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("alice-id")
                .claim(JwtClaimNames.PREFERRED_USERNAME, "alice")
                .claim(JwtClaimNames.REALM_ACCESS, Map.of(JwtClaimNames.ROLES, List.of("mto-ops")))
                .claim(JwtClaimNames.RESOURCE_ACCESS, Map.of("mto-notification-api", Map.of(JwtClaimNames.ROLES, List.of("notification-inbox"))))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_NOTIFICATION_INBOX"), "alice"));
        when(activityIngestor.ingest(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // --- la factoria ---

    @Test
    void aNotificationIsBornWithItsAudiencesAndOneAudienceDeliveryPerPushingChannel() {
        Notification created = create("access-login-streak", ActivitySeverity.CRITICAL, List.of(OPS, ALICE), List.of("inbox", "email"));
        Notification inboxOnly = create("users-change", ActivitySeverity.WARNING, List.of(OPS), List.of("inbox"));
        flushAndClear();

        assertEquals(2, audienceRepository.findByIdNotificationIdIn(List.of(created.getId())).size());
        List<Delivery> deliveries = deliveryRepository.findByNotificationIdOrderByCreatedAtAsc(created.getId());
        assertEquals(2, deliveries.size(), "una por audiencia, solo para el correo");
        assertTrue(deliveries.stream().allMatch(delivery -> delivery.getScope() == DeliveryScope.AUDIENCE
                && delivery.getStatus() == DeliveryStatus.PENDING && "email".equals(delivery.getChannel()) && delivery.getMaxAttempts() == 3));
        assertTrue(deliveryRepository.findByNotificationIdOrderByCreatedAtAsc(inboxOnly.getId()).isEmpty());
        verify(dispatchTrigger, times(1)).requestDispatchAfterCommit();

        assertEquals(0, deliveryRepository.insertAudienceIfMissing(created.getId(), "email", "PROFILE", OPS.toKey(), 3), "idempotente");
        assertEquals("alice", notificationRepository.findById(created.getId()).orElseThrow().getCreatedBy());
    }

    // --- la bandeja ---

    @Test
    void theInboxShowsWhatIsAddressedToMyUserOrMyProfilesNewestFirst() {
        Notification toOps = create("r", ActivitySeverity.INFO, List.of(OPS), List.of("inbox"));
        Notification toBob = create("r", ActivitySeverity.INFO, List.of(BOB), List.of("inbox"));
        Notification toAlice = create("r", ActivitySeverity.WARNING, List.of(ALICE, BOB), List.of("inbox"));
        flushAndClear();

        List<InboxItemResponse> items = inboxQueryService.myInbox(all(), PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))).content();

        assertEquals(List.of(toAlice.getId(), toOps.getId()), items.stream().map(InboxItemResponse::id).toList());
        assertTrue(items.stream().noneMatch(item -> item.id().equals(toBob.getId())));
        assertTrue(items.stream().noneMatch(InboxItemResponse::read));
        assertEquals(1, inboxQueryService.myInbox(new InboxFilter(null, null, ActivitySeverity.WARNING, null, null), PageRequest.of(0, 20)).content().size());
        assertTrue(notificationRepository.isVisibleTo(toOps.getId(), List.of(OPS.toKey())));
        assertFalse(notificationRepository.isVisibleTo(toBob.getId(), List.of(ALICE.toKey(), OPS.toKey())));
    }

    @Test
    void readingOneIsARecordAndReadingAllIsAMarkThatNewerOnesEscape() {
        Notification first = create("r", ActivitySeverity.INFO, List.of(OPS), List.of("inbox"));
        Notification second = create("r", ActivitySeverity.INFO, List.of(ALICE), List.of("inbox"));
        flushAndClear();

        InboxItemResponse read = inboxQueryService.markRead(first.getId());
        assertTrue(read.read());
        assertNotNull(read.readAt());
        assertEquals(List.of(second.getId()), inboxQueryService.myInbox(new InboxFilter(true, null, null, null, null), PageRequest.of(0, 20))
                .content().stream().map(InboxItemResponse::id).toList());
        assertEquals(new UnreadCountResponse(1, false), inboxQueryService.unreadCount());

        ReadAllResponse all = inboxQueryService.markAllRead();
        flushAndClear();
        assertEquals(second.getCreatedAt().toEpochMilli(), all.allReadUntil().toEpochMilli(), "hasta la mas reciente visible, no hasta ahora");
        assertEquals(new UnreadCountResponse(0, false), inboxQueryService.unreadCount());
        assertTrue(inboxQueryService.myInbox(all(), PageRequest.of(0, 20)).content().stream().allMatch(InboxItemResponse::read));

        Notification third = create("r", ActivitySeverity.INFO, List.of(OPS), List.of("inbox"));
        flushAndClear();
        assertEquals(List.of(third.getId()), inboxQueryService.myInbox(new InboxFilter(true, null, null, null, null), PageRequest.of(0, 20))
                .content().stream().map(InboxItemResponse::id).toList());
        assertTrue(inboxQueryService.markAllRead().allReadUntil().isAfter(all.allReadUntil()));
    }

    @Test
    void theUnreadCountIsCappedAndANotificationThatIsNotMineIsNotFound() {
        create("r", ActivitySeverity.INFO, List.of(OPS), List.of("inbox"));
        create("r", ActivitySeverity.INFO, List.of(OPS), List.of("inbox"));
        create("r", ActivitySeverity.INFO, List.of(ALICE), List.of("inbox"));
        Notification bobs = create("r", ActivitySeverity.INFO, List.of(BOB), List.of("inbox"));
        flushAndClear();

        assertEquals(new UnreadCountResponse(2, true), inboxQueryService.unreadCount());
        assertThrows(NotFoundException.class, () -> inboxQueryService.markRead(bobs.getId()));
        assertThrows(NotFoundException.class, () -> inboxQueryService.markRead(UUID.randomUUID()));
    }

    // --- las entregas ---

    @Test
    void anAudienceDeliveryIsClaimedExpandedIntoRecipientsAndSentOnce() {
        Notification notification = create("r", ActivitySeverity.CRITICAL, List.of(OPS), List.of("inbox", "email"));
        flushAndClear();

        List<Delivery> claimed = relay.claimDue(10);
        assertEquals(1, claimed.size());
        Delivery audience = claimed.getFirst();
        assertEquals(DeliveryStatus.IN_PROGRESS, audience.getStatus());
        assertEquals(1, audience.getAttempts());
        assertNotNull(audience.getClaimedAt());
        assertTrue(relay.claimDue(10).isEmpty(), "reclamada: invisible hasta que venza el reclamo");
        assertEquals(notification.getId(), relay.loadNotification(audience).orElseThrow().getId());

        assertEquals(2, relay.expand(audience, List.of(new Recipient("ops", "ops@mto.local"), new Recipient("sin-correo", null))));
        assertEquals(0, relay.expand(audience, List.of(new Recipient("ops", "ops@mto.local"))), "por notificacion, canal y direccion");
        flushAndClear();
        List<Delivery> all = deliveryRepository.findByNotificationIdOrderByCreatedAtAsc(notification.getId());
        assertEquals(3, all.size());
        assertEquals(DeliveryStatus.SENT, all.stream().filter(delivery -> delivery.getScope() == DeliveryScope.AUDIENCE).findFirst().orElseThrow().getStatus(),
                "la de audiencia queda expandida");
        Delivery recipient = all.stream().filter(delivery -> "ops@mto.local".equals(delivery.getRecipient())).findFirst().orElseThrow();
        assertEquals(DeliveryStatus.PENDING, recipient.getStatus());
        assertEquals("ops", recipient.getRecipientUsername());
        Delivery skipped = all.stream().filter(delivery -> "sin-correo".equals(delivery.getRecipient())).findFirst().orElseThrow();
        assertEquals(DeliveryStatus.SKIPPED, skipped.getStatus());
        assertTrue(skipped.getLastError().contains("no address"));

        List<Delivery> due = relay.claimDue(10);
        assertEquals(List.of(recipient.getId()), due.stream().map(Delivery::getId).toList());
        relay.markSent(due.getFirst());
        flushAndClear();
        assertEquals(DeliveryStatus.SENT, deliveryRepository.findById(recipient.getId()).orElseThrow().getStatus());
        assertNotNull(deliveryRepository.findById(recipient.getId()).orElseThrow().getSentAt());
        assertTrue(relay.isOverHourlyLimit("email", "ops@mto.local", 1));
        assertFalse(relay.isOverHourlyLimit("email", "ops@mto.local", 2));
    }

    @Test
    void aFailedDeliveryIsRescheduledThenDeadAndARetryQueuesItAgain() {
        Notification notification = create("r", ActivitySeverity.CRITICAL, List.of(OPS), List.of("email"));
        flushAndClear();
        Delivery delivery = relay.claimDue(10).getFirst();

        relay.reschedule(delivery, Instant.now().plusSeconds(3600), "smtp down");
        flushAndClear();
        Delivery rescheduled = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PENDING, rescheduled.getStatus());
        assertEquals("smtp down", rescheduled.getLastError());
        assertTrue(relay.claimDue(10).isEmpty(), "no vence hasta dentro de una hora");

        entityManager.createNativeQuery("update delivery set next_attempt_at = now() where id = :id").setParameter("id", delivery.getId()).executeUpdate();
        flushAndClear();
        Delivery again = relay.claimDue(10).getFirst();
        assertEquals(2, again.getAttempts());
        relay.markDead(again, "smtp down for good");
        flushAndClear();
        assertEquals(DeliveryStatus.FAILED, deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus());
        ArgumentCaptor<ActivityEventDraft> dead = ArgumentCaptor.forClass(ActivityEventDraft.class);
        verify(activityIngestor).ingest(dead.capture());
        assertEquals(ActivityTypes.SYSTEM_DELIVERY_DEAD, dead.getValue().type());
        assertEquals("delivery-dead:" + delivery.getId(), dead.getValue().sourceEventId());
        assertEquals(notification.getId().toString(), dead.getValue().payload().get("notificationId"));

        assertEquals(1, deliveryRepository.retry(delivery.getId()));
        assertEquals(0, deliveryRepository.retry(delivery.getId()), "ya esta PENDING");
        flushAndClear();
        Delivery retried = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PENDING, retried.getStatus());
        assertEquals(0, retried.getAttempts());
        assertNull(retried.getLastError());
        assertEquals(1, relay.claimDue(10).size());
    }

    @Test
    void aSkippedDeliveryIsSettledWithItsReasonAndNothingIsIngested() {
        create("r", ActivitySeverity.INFO, List.of(OPS), List.of("email"));
        flushAndClear();
        Delivery delivery = relay.claimDue(10).getFirst();

        relay.markSkipped(delivery, "no channel 'email' is configured");
        flushAndClear();

        Delivery skipped = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.SKIPPED, skipped.getStatus());
        assertEquals("no channel 'email' is configured", skipped.getLastError());
        verify(activityIngestor, never()).ingest(any());
        assertEquals(1, deliveryRepository.countGroupedByStatus().size());
        assertEquals(DeliveryStatus.SKIPPED, deliveryRepository.countGroupedByStatus().getFirst().getStatus());
    }

    // --- soporte ---

    private Notification create(String ruleKey, ActivitySeverity severity, List<Audience> audiences, List<String> channels) {
        return notificationFactory.create(new NotificationFactory.NotificationDraft(ruleKey, null, ActivityCategory.ACCESS, severity,
                "Titulo " + ruleKey, "cuerpo", "/ruta", "user", "u1", audiences, channels));
    }

    private static InboxFilter all() {
        return new InboxFilter(null, null, null, null, null);
    }

    /** Lo que en produccion hace el commit: sin el flush, clear() tiraria lo que aun no se escribio. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
