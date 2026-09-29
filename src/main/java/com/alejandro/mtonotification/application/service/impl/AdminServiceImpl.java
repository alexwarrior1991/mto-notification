package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.admin.BroadcastRequest;
import com.alejandro.mtonotification.application.dto.admin.RuleResponse;
import com.alejandro.mtonotification.application.dto.admin.RulesResponse;
import com.alejandro.mtonotification.application.dto.admin.SourceStatusResponse;
import com.alejandro.mtonotification.application.dto.admin.TestEmailRequest;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryFilter;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryResponse;
import com.alejandro.mtonotification.application.dto.notification.NotificationResponse;
import com.alejandro.mtonotification.application.exception.ConflictException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.UnprocessableException;
import com.alejandro.mtonotification.application.mapper.DeliveryMapper;
import com.alejandro.mtonotification.application.mapper.NotificationMapper;
import com.alejandro.mtonotification.application.mapper.PageMapper;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.AdminService;
import com.alejandro.mtonotification.application.service.DeliveryDispatchTrigger;
import com.alejandro.mtonotification.application.service.NotificationFactory;
import com.alejandro.mtonotification.application.service.RuleRepository;
import com.alejandro.mtonotification.application.service.SourceCursorService;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.configuration.security.CurrentUserService;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.domain.model.DeliveryChannels;
import com.alejandro.mtonotification.domain.model.NotificationRule;
import com.alejandro.mtonotification.domain.model.Subject;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityBurstStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationAudience;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityBurstRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.DeliveryRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.InboxMessageRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationAudienceRepository;
import com.alejandro.mtonotification.infrastructure.persistence.specification.DeliverySpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class AdminServiceImpl implements AdminService {

    static final Set<String> DELIVERY_SORTABLE = Set.of("createdAt", "status", "nextAttemptAt", "channel", "sentAt");
    static final String BROADCAST_RULE_KEY = "manual-broadcast";
    static final String TEST_EMAIL_RULE_KEY = "test-email";
    static final String SELF_SOURCE = "mto-notification";

    private final RuleRepository ruleRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryMapper deliveryMapper;
    private final DeliveryDispatchTrigger dispatchTrigger;
    private final SourceCursorService cursorService;
    private final InboxMessageRepository inboxMessageRepository;
    private final ActivityBurstRepository burstRepository;
    private final ActivityIngestor activityIngestor;
    private final NotificationFactory notificationFactory;
    private final NotificationAudienceRepository audienceRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserService currentUser;
    private final NotificationProperties properties;

    @Override
    public RulesResponse rules() {
        List<RuleResponse> rules = new ArrayList<>();
        for (NotificationRule rule : ruleRepository.rules()) {
            rules.add(new RuleResponse(rule.key(), rule.matcher().describe(), rule.when(), rule.severity(), rule.audiences(),
                    rule.channels(), rule.title(), rule.body(), rule.link(),
                    rule.throttle() == null ? null : rule.throttle().window(),
                    rule.throttle() == null ? null : rule.throttle().key()));
        }
        return new RulesResponse(ruleRepository.source(), ruleRepository.variables(), List.copyOf(rules));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> deliveries(DeliveryFilter filter, Pageable pageable) {
        Pageable sanitized = SortWhitelist.sanitize(pageable, DELIVERY_SORTABLE, properties.inbox().maxPageSize());
        Specification<Delivery> spec = DeliverySpecification.statusEquals(filter.status())
                .and(DeliverySpecification.channelEquals(filter.channel()))
                .and(DeliverySpecification.notificationIdEquals(filter.notificationId()));
        return PageMapper.toPageResponse(deliveryRepository.findAll(spec, sanitized), deliveryMapper::toResponse);
    }

    @Override
    @Transactional
    public DeliveryResponse retry(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId).orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        if (delivery.getStatus() != DeliveryStatus.FAILED && delivery.getStatus() != DeliveryStatus.SKIPPED) {
            throw new ConflictException("Delivery", "Delivery " + deliveryId + " is " + delivery.getStatus() + " and only FAILED or SKIPPED deliveries can be retried");
        }
        if (deliveryRepository.retry(deliveryId) == 0) {
            throw new ConflictException("Delivery", "Delivery " + deliveryId + " changed while retrying it");
        }
        dispatchTrigger.requestDispatchAfterCommit();
        return deliveryMapper.toResponse(deliveryRepository.findById(deliveryId).orElseThrow(() -> new NotFoundException("Delivery", deliveryId)));
    }

    @Override
    @Transactional(readOnly = true)
    public SourceStatusResponse sources() {
        List<SourceStatusResponse.CursorResponse> cursors = cursorService.all().stream()
                .map(cursor -> new SourceStatusResponse.CursorResponse(cursor.getKind(), cursor.getLastEventTime(),
                        cursor.getLeaseOwner(), cursor.getLeaseUntil(), cursor.getLastPollAt(), cursor.getLastSuccessAt(),
                        cursor.getLastError()))
                .toList();
        List<SourceStatusResponse.InboxCountResponse> inbox = inboxMessageRepository.countBySourceAndStatus().stream()
                .map(count -> new SourceStatusResponse.InboxCountResponse(count.getSourceService(), count.getStatus(), count.getTotal()))
                .toList();
        Map<DeliveryStatus, Long> deliveries = new EnumMap<>(DeliveryStatus.class);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            deliveries.put(status, 0L);
        }
        deliveryRepository.countGroupedByStatus().forEach(count -> deliveries.put(count.getStatus(), count.getTotal()));
        return new SourceStatusResponse(cursors, inbox, burstRepository.countByStatus(ActivityBurstStatus.OPEN), deliveries);
    }

    @Override
    @Transactional
    public NotificationResponse sendTestEmail(TestEmailRequest request) {
        String username = currentUser.getUsername().orElse(null);
        UUID token = UUID.randomUUID();
        ActivityEvent event = activityIngestor.ingest(ActivityEventDraft.builder()
                .source(SELF_SOURCE, "test-email:" + token)
                .type(ActivityTypes.SYSTEM_TEST_EMAIL)
                .occurredAt(Instant.now())
                .actor(actor())
                .subject(Subject.of("email", request.to()))
                .payload(Map.of("to", request.to()))
                .build()).orElseThrow();

        List<Audience> audiences = username == null ? List.of() : List.of(Audience.user(username));
        Notification notification = notificationFactory.create(new NotificationFactory.NotificationDraft(
                TEST_EMAIL_RULE_KEY, event.getId(), ActivityCategory.SYSTEM, ActivitySeverity.INFO,
                "Correo de prueba de mto-notification",
                "Si lees esto por correo, el canal funciona. Enviado a " + request.to() + (username == null ? "" : " por " + username) + ".",
                "/notificaciones", "email", request.to(), audiences, List.of(DeliveryChannels.INBOX)));
        // La entrega va directa a la direccion pedida: no pasa por el directorio.
        deliveryRepository.insertRecipientIfMissing(notification.getId(), DeliveryChannels.EMAIL, request.to().trim(),
                username, DeliveryStatus.PENDING.name(), properties.delivery().maxAttempts(), null);
        dispatchTrigger.requestDispatchAfterCommit();
        return response(notification);
    }

    @Override
    @Transactional
    public NotificationResponse broadcast(BroadcastRequest request) {
        List<Audience> audiences = new ArrayList<>(new LinkedHashSet<>(request.audiences().stream()
                .map(key -> Audience.parse(key).orElseThrow(() -> new UnprocessableException("Notification",
                        "Audience '" + key + "' is not <KIND>:<key> with a known kind (USER, USER_ID, PROFILE, CLIENT_ROLE)")))
                .toList()));
        List<String> channels = request.channels() == null || request.channels().isEmpty()
                ? List.of(DeliveryChannels.INBOX)
                : request.channels().stream().map(channel -> channel.trim().toLowerCase()).distinct().toList();
        for (String channel : channels) {
            if (!DeliveryChannels.isKnown(channel)) {
                throw new UnprocessableException("Notification", "Channel '" + channel + "' is unknown; known channels are " + DeliveryChannels.KNOWN);
            }
        }
        ActivitySeverity severity = request.severity() == null ? ActivitySeverity.INFO : request.severity();
        UUID token = UUID.randomUUID();
        ActivityEvent event = activityIngestor.ingest(ActivityEventDraft.builder()
                .source(SELF_SOURCE, "broadcast:" + token)
                .type(ActivityTypes.SYSTEM_BROADCAST)
                .severity(severity)
                .occurredAt(Instant.now())
                .actor(actor())
                .subject(Subject.of("broadcast", token.toString(), request.title()))
                .payload(Map.of("title", request.title(), "audiences", audiences.stream().map(Audience::toKey).toList(),
                        "channels", channels))
                .build()).orElseThrow();

        Notification notification = notificationFactory.create(new NotificationFactory.NotificationDraft(
                BROADCAST_RULE_KEY, event.getId(), ActivityCategory.SYSTEM, severity, request.title().trim(),
                request.body() == null || request.body().isBlank() ? null : request.body().trim(),
                request.link() == null || request.link().isBlank() ? null : request.link().trim(),
                "broadcast", token.toString(), audiences, channels));
        return response(notification);
    }

    private Actor actor() {
        return Actor.ofUsername(currentUser.getUsername().orElse(null), currentUser.getUserId().orElse(null));
    }

    private NotificationResponse response(Notification notification) {
        List<String> audiences = audienceRepository.findByIdNotificationIdIn(List.of(notification.getId())).stream()
                .map(NotificationAudience::getId)
                .map(NotificationAudience.Id::getAudienceKey)
                .sorted()
                .toList();
        return notificationMapper.toResponse(notification, audiences);
    }
}
