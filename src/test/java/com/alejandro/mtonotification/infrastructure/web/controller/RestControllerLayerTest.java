package com.alejandro.mtonotification.infrastructure.web.controller;

import com.alejandro.mtonotification.application.dto.access.AccessEventFilter;
import com.alejandro.mtonotification.application.dto.access.AccessEventResponse;
import com.alejandro.mtonotification.application.dto.access.AccessOutcome;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventFilter;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventResponse;
import com.alejandro.mtonotification.application.dto.activity.ActorResponse;
import com.alejandro.mtonotification.application.dto.activity.SubjectResponse;
import com.alejandro.mtonotification.application.dto.admin.BroadcastRequest;
import com.alejandro.mtonotification.application.dto.admin.RuleResponse;
import com.alejandro.mtonotification.application.dto.admin.RulesResponse;
import com.alejandro.mtonotification.application.dto.admin.SourceStatusResponse;
import com.alejandro.mtonotification.application.dto.admin.TestEmailRequest;
import com.alejandro.mtonotification.application.dto.common.PageMetadataResponse;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryFilter;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryResponse;
import com.alejandro.mtonotification.application.dto.inbox.InboxFilter;
import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.inbox.ReadAllResponse;
import com.alejandro.mtonotification.application.dto.inbox.UnreadCountResponse;
import com.alejandro.mtonotification.application.dto.notification.NotificationResponse;
import com.alejandro.mtonotification.application.exception.ConflictException;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import com.alejandro.mtonotification.application.exception.InvalidSortException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.UnprocessableException;
import com.alejandro.mtonotification.application.exception.ValidationException;
import com.alejandro.mtonotification.application.service.ActivityQueryService;
import com.alejandro.mtonotification.application.service.AdminService;
import com.alejandro.mtonotification.application.service.InboxQueryService;
import com.alejandro.mtonotification.configuration.security.RestAccessDeniedHandler;
import com.alejandro.mtonotification.configuration.security.RestAuthenticationEntryPoint;
import com.alejandro.mtonotification.configuration.security.SecurityConfiguration;
import com.alejandro.mtonotification.configuration.security.SecurityRoles;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.ActorKind;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.web.NotificationApiPaths;
import com.alejandro.mtonotification.infrastructure.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los cuatro controladores con la cadena de seguridad real y los servicios sustituidos: lo que se
 * fija es la forma de cada ruta (parametros, paginacion, cuerpo, codigos y errores), no la logica.
 */
@WebMvcTest(controllers = {InboxController.class, ActivityController.class, AccessController.class, AdminController.class})
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.security.client-id=mto-notification-api",
        "app.security.principal-claim=preferred_username",
        "app.security.audience-validation-enabled=false",
        "app.security.expose-api-docs=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8082/realms/mto"
})
class RestControllerLayerTest {

    private static final String INBOX = NotificationApiPaths.BASE + NotificationApiPaths.INBOX;
    private static final String ACTIVITY = NotificationApiPaths.BASE + NotificationApiPaths.ACTIVITY;
    private static final String ACCESS = NotificationApiPaths.BASE + NotificationApiPaths.ACCESS;
    private static final String ADMIN = NotificationApiPaths.BASE + NotificationApiPaths.ADMIN;

    private static final Instant CREATED_AT = Instant.parse("2026-09-28T10:15:30Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InboxQueryService inboxQueryService;

    @MockitoBean
    private ActivityQueryService activityQueryService;

    @MockitoBean
    private AdminService adminService;

    // --- bandeja ---

    @Test
    void theInboxIsPagedFilteredAndSortedFromTheQueryString() throws Exception {
        UUID id = UUID.randomUUID();
        when(inboxQueryService.myInbox(any(), any())).thenReturn(page(List.of(item(id, false, null))));

        mockMvc.perform(get(INBOX).param("unread", "true").param("category", "ACCESS").param("severity", "CRITICAL")
                        .param("from", "2026-09-01T00:00:00Z").param("page", "1").param("size", "5").param("sort", "severity,asc")
                        .with(role(SecurityRoles.NOTIFICATION_INBOX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-09-28T10:15:30Z"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.size").value(20));

        ArgumentCaptor<InboxFilter> filter = ArgumentCaptor.forClass(InboxFilter.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(inboxQueryService).myInbox(filter.capture(), pageable.capture());
        assertEquals(new InboxFilter(true, ActivityCategory.ACCESS, ActivitySeverity.CRITICAL, Instant.parse("2026-09-01T00:00:00Z"), null),
                filter.getValue());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(5, pageable.getValue().getPageSize());
        assertEquals(Sort.Direction.ASC, pageable.getValue().getSort().getOrderFor("severity").getDirection());
    }

    @Test
    void withoutSortTheInboxComesNewestFirstByDefault() throws Exception {
        when(inboxQueryService.myInbox(any(), any())).thenReturn(page(List.of()));

        mockMvc.perform(get(INBOX).with(role(SecurityRoles.NOTIFICATION_INBOX))).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(inboxQueryService).myInbox(any(), pageable.capture());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getValue().getSort().getOrderFor("createdAt").getDirection());
    }

    @Test
    void theUnreadCountAndTheMarksAnswerWithTheirSmallBodies() throws Exception {
        UUID id = UUID.randomUUID();
        when(inboxQueryService.unreadCount()).thenReturn(new UnreadCountResponse(100, true));
        when(inboxQueryService.markRead(id)).thenReturn(item(id, true, CREATED_AT.plusSeconds(60)));
        when(inboxQueryService.markAllRead()).thenReturn(new ReadAllResponse(CREATED_AT));

        mockMvc.perform(get(INBOX + "/unread-count").with(role(SecurityRoles.NOTIFICATION_INBOX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(100))
                .andExpect(jsonPath("$.capped").value(true));
        mockMvc.perform(post(INBOX + "/" + id + "/read").with(role(SecurityRoles.NOTIFICATION_INBOX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").value("2026-09-28T10:16:30Z"));
        mockMvc.perform(post(INBOX + "/read-all").with(role(SecurityRoles.NOTIFICATION_INBOX)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allReadUntil").value("2026-09-28T10:15:30Z"));
    }

    @Test
    void aNotificationThatIsNotMineIs404WithTheNotificationCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(inboxQueryService.markRead(id)).thenThrow(new NotFoundException("Notification", id));

        mockMvc.perform(post(INBOX + "/" + id + "/read").with(role(SecurityRoles.NOTIFICATION_INBOX)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NTF-404"))
                .andExpect(jsonPath("$.path").value(INBOX + "/" + id + "/read"));
        mockMvc.perform(post(INBOX + "/not-a-uuid/read").with(role(SecurityRoles.NOTIFICATION_INBOX)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"));
    }

    // --- registro ---

    @Test
    void theLogIsSearchedWithEveryFilterAndAnUnknownSortIs400() throws Exception {
        UUID id = UUID.randomUUID();
        when(activityQueryService.search(any(), any())).thenReturn(page(List.of(activity(id))));

        mockMvc.perform(get(ACTIVITY).param("category", "USERS").param("type", ActivityTypes.USERS_ADMIN_USER_UPDATED)
                        .param("actorUsername", "alice").param("subjectType", "user").param("subjectId", "u1")
                        .param("severity", "WARNING").param("sourceService", "keycloak-admin")
                        .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-30T00:00:00Z").param("includeSuperseded", "true")
                        .with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].actor.username").value("alice"))
                .andExpect(jsonPath("$.content[0].type").value(ActivityTypes.USERS_ADMIN_USER_UPDATED));

        ArgumentCaptor<ActivityEventFilter> filter = ArgumentCaptor.forClass(ActivityEventFilter.class);
        verify(activityQueryService).search(filter.capture(), any());
        assertEquals(new ActivityEventFilter(ActivityCategory.USERS, ActivityTypes.USERS_ADMIN_USER_UPDATED, "alice", "user", "u1",
                ActivitySeverity.WARNING, "keycloak-admin", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"), true),
                filter.getValue());

        when(activityQueryService.search(any(), any())).thenThrow(new InvalidSortException("payload"));
        mockMvc.perform(get(ACTIVITY).param("sort", "payload,asc").with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("sort"));
    }

    @Test
    void askingTheLogForAccessesIs400AndAMissingEventIs404() throws Exception {
        when(activityQueryService.search(any(), any())).thenThrow(new ValidationException("Access events are served by /access"));
        mockMvc.perform(get(ACTIVITY).param("category", "ACCESS").with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-001"));

        UUID id = UUID.randomUUID();
        when(activityQueryService.get(id)).thenThrow(new NotFoundException("Activity event", id));
        mockMvc.perform(get(ACTIVITY + "/" + id).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACT-404"));

        mockMvc.perform(get(ACTIVITY).param("category", "NOPE").with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("category"));
    }

    @Test
    void theDetailCarriesThePayloadAsJson() throws Exception {
        UUID id = UUID.randomUUID();
        when(activityQueryService.get(id)).thenReturn(activity(id));

        mockMvc.perform(get(ACTIVITY + "/" + id).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.clientId").value("admin-cli"))
                .andExpect(jsonPath("$.subject.label").value("Alice"))
                .andExpect(jsonPath("$.eventCount").value(1));
    }

    // --- accesos ---

    @Test
    void theAccessesAreSearchedByUserIpAndOutcome() throws Exception {
        UUID id = UUID.randomUUID();
        when(activityQueryService.searchAccess(any(), any())).thenReturn(page(List.of(new AccessEventResponse(id, 7L,
                ActivityTypes.ACCESS_LOGIN_FAILED, ActivitySeverity.WARNING, AccessOutcome.FAILURE, CREATED_AT, CREATED_AT,
                "alice", "u1", "10.0.0.1", null, 1, Map.of("error", "invalid_user_credentials")))));

        mockMvc.perform(get(ACCESS).param("username", "alice").param("ipAddress", "10.0.0.1").param("outcome", "FAILURE")
                        .param("type", ActivityTypes.ACCESS_LOGIN_FAILED).with(role(SecurityRoles.NOTIFICATION_ACCESS_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].outcome").value("FAILURE"))
                .andExpect(jsonPath("$.content[0].ipAddress").value("10.0.0.1"))
                .andExpect(jsonPath("$.content[0].payload.error").value("invalid_user_credentials"));

        ArgumentCaptor<AccessEventFilter> filter = ArgumentCaptor.forClass(AccessEventFilter.class);
        verify(activityQueryService).searchAccess(filter.capture(), any());
        assertEquals(new AccessEventFilter("alice", "10.0.0.1", ActivityTypes.ACCESS_LOGIN_FAILED, AccessOutcome.FAILURE, null, null),
                filter.getValue());
    }

    // --- administracion ---

    @Test
    void theRulesTheSourcesAndTheDeliveriesAreListed() throws Exception {
        when(adminService.rules()).thenReturn(new RulesResponse("classpath:notification-rules.yml", Map.of("large-burst-threshold", 50),
                List.of(new RuleResponse("access-login-streak", List.of(ActivityTypes.ACCESS_LOGIN_STREAK), null, ActivitySeverity.CRITICAL,
                        List.of("PROFILE:mto-ops"), List.of("inbox", "email"), "t", null, null, Duration.ofMinutes(30), "#{payload.value}"))));
        when(adminService.sources()).thenReturn(new SourceStatusResponse(
                List.of(new SourceStatusResponse.CursorResponse(SourceKind.KEYCLOAK_LOGIN, CREATED_AT, null, null, CREATED_AT, CREATED_AT, null)),
                List.of(new SourceStatusResponse.InboxCountResponse("keycloak-login", InboxMessageStatus.PROCESSED, 12)),
                2, Map.of(DeliveryStatus.PENDING, 1L)));
        UUID deliveryId = UUID.randomUUID();
        when(adminService.deliveries(any(), any())).thenReturn(page(List.of(delivery(deliveryId, DeliveryStatus.FAILED))));

        mockMvc.perform(get(ADMIN + "/rules").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].key").value("access-login-streak"))
                .andExpect(jsonPath("$.rules[0].throttleWindow").value("PT30M"))
                .andExpect(jsonPath("$.variables.large-burst-threshold").value(50));
        mockMvc.perform(get(ADMIN + "/sources").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cursors[0].kind").value("KEYCLOAK_LOGIN"))
                .andExpect(jsonPath("$.inbox[0].total").value(12))
                .andExpect(jsonPath("$.openBursts").value(2))
                .andExpect(jsonPath("$.deliveries.PENDING").value(1));
        mockMvc.perform(get(ADMIN + "/deliveries").param("status", "FAILED").param("channel", "email")
                        .param("notificationId", deliveryId.toString()).with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));

        ArgumentCaptor<DeliveryFilter> filter = ArgumentCaptor.forClass(DeliveryFilter.class);
        verify(adminService).deliveries(filter.capture(), any());
        assertEquals(new DeliveryFilter(DeliveryStatus.FAILED, "email", deliveryId), filter.getValue());
    }

    @Test
    void aRetryAnswersTheDeliveryOr409WhenItsStatusDoesNotAdmitIt() throws Exception {
        UUID ok = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        when(adminService.retry(ok)).thenReturn(delivery(ok, DeliveryStatus.PENDING));
        when(adminService.retry(sent)).thenThrow(new ConflictException("Delivery", "Delivery is SENT"));

        mockMvc.perform(post(ADMIN + "/deliveries/" + ok + "/retry").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post(ADMIN + "/deliveries/" + sent + "/retry").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DLV-409"));
    }

    @Test
    void aTestEmailIsAcceptedAndABadAddressIsRejectedBeforeTheService() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminService.sendTestEmail(new TestEmailRequest("ops@mto.local"))).thenReturn(notification(id));

        mockMvc.perform(post(ADMIN + "/test-email").contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"ops@mto.local\"}")
                        .with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(post(ADMIN + "/test-email").contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"no-es-un-correo\"}")
                        .with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("to"));
    }

    @Test
    void aBroadcastIsCreatedWithItsLocationAndAnUnknownAudienceIs422() throws Exception {
        UUID id = UUID.randomUUID();
        when(adminService.broadcast(any())).thenReturn(notification(id));

        mockMvc.perform(post(ADMIN + "/broadcasts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Parada programada", "body": "El sabado", "link": "/avisos", "severity": "WARNING",
                                 "audiences": ["PROFILE:mto-ops", "USER:alice"], "channels": ["inbox", "email"]}""")
                        .with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost" + ADMIN + "/broadcasts/" + id))
                .andExpect(jsonPath("$.audiences[0]").value("PROFILE:mto-ops"));

        ArgumentCaptor<BroadcastRequest> request = ArgumentCaptor.forClass(BroadcastRequest.class);
        verify(adminService).broadcast(request.capture());
        assertEquals(List.of("PROFILE:mto-ops", "USER:alice"), request.getValue().audiences());
        assertEquals(ActivitySeverity.WARNING, request.getValue().severity());

        when(adminService.broadcast(any())).thenThrow(new UnprocessableException("Notification", "Audience 'TEAM:x' is unknown"));
        mockMvc.perform(post(ADMIN + "/broadcasts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"t\", \"audiences\": [\"TEAM:x\"]}").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("NTF-422"));
        mockMvc.perform(post(ADMIN + "/broadcasts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \" \", \"audiences\": []}").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"));
    }

    @Test
    void aDirectoryOutageIs503WithItsOwnCode() throws Exception {
        when(adminService.sources()).thenThrow(new DirectoryUnavailableException("Keycloak is not answering"));

        mockMvc.perform(get(ADMIN + "/sources").with(role(SecurityRoles.NOTIFICATION_ADMIN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("NTF-503"));
    }

    @Test
    void theRealControllersSitBehindTheirPermissions() throws Exception {
        mockMvc.perform(get(INBOX).with(role(SecurityRoles.NOTIFICATION_ADMIN))).andExpect(status().isForbidden());
        mockMvc.perform(get(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_INBOX))).andExpect(status().isForbidden());
        mockMvc.perform(get(ACCESS).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ))).andExpect(status().isForbidden());
        mockMvc.perform(get(ADMIN + "/rules").with(role(SecurityRoles.NOTIFICATION_ACCESS_READ))).andExpect(status().isForbidden());
        mockMvc.perform(get(INBOX)).andExpect(status().isUnauthorized());
    }

    // --- soporte ---

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(jwt -> jwt.claim("preferred_username", "alice"))
                .authorities(AuthorityUtils.createAuthorityList("ROLE_" + role));
    }

    private static <T> PageResponse<T> page(List<T> content) {
        return new PageResponse<>(content, new PageMetadataResponse(0, 20, content.size(), 1, true, true));
    }

    private static InboxItemResponse item(UUID id, boolean read, Instant readAt) {
        return new InboxItemResponse(id, "access-login-streak", ActivityCategory.ACCESS, ActivitySeverity.CRITICAL,
                "Racha", "3 fallos", "/actividad/accesos", "user", "u1", UUID.randomUUID(), CREATED_AT, read, readAt);
    }

    private static ActivityEventResponse activity(UUID id) {
        return new ActivityEventResponse(id, 3L, "keycloak-admin", "abc", ActivityCategory.USERS, ActivityTypes.USERS_ADMIN_USER_UPDATED,
                ActivitySeverity.WARNING, CREATED_AT, CREATED_AT, new ActorResponse(ActorKind.PERSON, "alice", "u1"),
                new SubjectResponse("user", "u2", "Alice"), "corr", 1, Map.of("clientId", "admin-cli"), null);
    }

    private static DeliveryResponse delivery(UUID id, DeliveryStatus status) {
        return new DeliveryResponse(id, UUID.randomUUID(), "email", DeliveryScope.RECIPIENT, null, "ops@mto.local", "ops",
                status, 1, 8, CREATED_AT, null, "smtp down", CREATED_AT);
    }

    private static NotificationResponse notification(UUID id) {
        return new NotificationResponse(id, "manual-broadcast", ActivityCategory.SYSTEM, ActivitySeverity.WARNING, "Parada programada",
                "El sabado", "/avisos", "broadcast", id.toString(), UUID.randomUUID(), List.of("PROFILE:mto-ops", "USER:alice"), CREATED_AT, "alice");
    }

    @SuppressWarnings("unused")
    private static void unused() {
        assertNull(null);
        assertTrue(true);
        eq(null);
    }
}
