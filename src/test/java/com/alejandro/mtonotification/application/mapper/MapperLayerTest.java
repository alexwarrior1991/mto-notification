package com.alejandro.mtonotification.application.mapper;

import com.alejandro.mtonotification.application.dto.access.AccessEventResponse;
import com.alejandro.mtonotification.application.dto.access.AccessOutcome;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventResponse;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryResponse;
import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.notification.NotificationResponse;
import com.alejandro.mtonotification.application.service.impl.JsonPayloads;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.ActorKind;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entidad → respuesta con los mappers generados, sin contexto de Spring. */
class MapperLayerTest {

    private final JsonPayloads jsonPayloads = new JsonPayloads(JsonMapper.builder().build());
    private final ActivityEventMapper activityEventMapper = activityEventMapper();
    private final NotificationMapper notificationMapper = new NotificationMapperImpl();
    private final DeliveryMapper deliveryMapper = new DeliveryMapperImpl();

    @Test
    void theDetailCarriesThePayloadAsAMapAndTheSummaryDoesNot() throws Exception {
        ActivityEvent event = event();
        ActivityEventResponse detail = activityEventMapper.toDetail(event);
        assertEquals(Map.of("username", "alice"), detail.payload());
        assertEquals(ActorKind.PERSON, detail.actor().kind());
        assertEquals("alice", detail.actor().username());
        assertEquals("user", detail.subject().type());
        assertEquals(ActivityCategory.ACCESS, detail.category());

        assertNull(activityEventMapper.toSummary(event).payload());
    }

    @Test
    void anAccessCarriesItsIpAndOutcome() throws Exception {
        AccessEventResponse access = activityEventMapper.toAccess(event());
        assertEquals("10.1.2.3", access.ipAddress());
        assertEquals("alice", access.username());
        assertEquals(AccessOutcome.FAILURE, access.outcome());
        assertEquals(AccessOutcome.SUCCESS, ActivityEventMapper.outcome(ActivityTypes.ACCESS_LOGIN));
        assertEquals(AccessOutcome.FAILURE, ActivityEventMapper.outcome(ActivityTypes.ACCESS_LOCKOUT));
    }

    @Test
    void inboxItemsAndAdminResponsesTakeWhatTheEntityDoesNotKnow() {
        Notification notification = Notification.builder().ruleKey("r").category(ActivityCategory.SYSTEM)
                .severity(ActivitySeverity.INFO).title("t").body("b").link("/x").build();
        ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(notification, "createdAt", Instant.parse("2026-09-28T10:00:00Z"));
        ReflectionTestUtils.setField(notification, "createdBy", "alice");

        Instant readAt = Instant.parse("2026-09-28T11:00:00Z");
        InboxItemResponse item = notificationMapper.toInboxItem(notification, true, readAt);
        assertTrue(item.read());
        assertEquals(readAt, item.readAt());
        assertEquals("t", item.title());
        assertEquals(notification.getId(), item.id());

        NotificationResponse response = notificationMapper.toResponse(notification, List.of("PROFILE:mto-ops"));
        assertEquals(List.of("PROFILE:mto-ops"), response.audiences());
        assertEquals("alice", response.createdBy());
    }

    @Test
    void aDeliveryMapsOneToOne() {
        Delivery delivery = Delivery.builder().notificationId(UUID.randomUUID()).channel("email").scope(DeliveryScope.RECIPIENT)
                .recipient("a@b.c").recipientUsername("alice").status(DeliveryStatus.SENT).attempts(1).maxAttempts(8)
                .nextAttemptAt(Instant.now()).build();
        DeliveryResponse response = deliveryMapper.toResponse(delivery);
        assertEquals("a@b.c", response.recipient());
        assertEquals(DeliveryStatus.SENT, response.status());
        assertEquals(8, response.maxAttempts());
    }

    private ActivityEventMapper activityEventMapper() {
        ActivityEventMapperImpl mapper = new ActivityEventMapperImpl();
        ReflectionTestUtils.setField(mapper, "jsonPayloads", jsonPayloads);
        return mapper;
    }

    private static ActivityEvent event() throws Exception {
        ActivityEvent event = new ActivityEventForTest();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        event.setSourceService("keycloak-login");
        event.setSourceEventId("abc");
        event.setCategory(ActivityCategory.ACCESS);
        event.setType(ActivityTypes.ACCESS_LOGIN_FAILED);
        event.setSeverity(ActivitySeverity.WARNING);
        event.setOccurredAt(Instant.now());
        event.setRecordedAt(Instant.now());
        event.setActorKind(ActorKind.PERSON);
        event.setActorUsername("alice");
        event.setActorId("u1");
        event.setSubjectType("user");
        event.setSubjectId("u1");
        event.setIpAddress(InetAddress.getByName("10.1.2.3"));
        event.setEventCount(1);
        event.setPayload("{\"username\":\"alice\"}");
        return event;
    }

    /** El constructor es protected: el mapper no construye entidades, pero el test si. */
    private static final class ActivityEventForTest extends ActivityEvent {
        private ActivityEventForTest() {
            super();
        }
    }
}
