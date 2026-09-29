package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.inbox.InboxFilter;
import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.inbox.ReadAllResponse;
import com.alejandro.mtonotification.application.dto.inbox.UnreadCountResponse;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.mapper.NotificationMapper;
import com.alejandro.mtonotification.application.mapper.PageMapper;
import com.alejandro.mtonotification.application.service.InboxQueryService;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.configuration.security.CurrentUserService;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxState;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationReceipt;
import com.alejandro.mtonotification.infrastructure.persistence.repository.InboxStateRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationReceiptRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationRepository;
import com.alejandro.mtonotification.infrastructure.persistence.specification.NotificationSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * La bandeja se resuelve al leer con las claves del token: nada se expandio al crear. Leida es un
 * recibo propio, o estar por debajo de la marca de «todas leidas»; el recibo gana. Una
 * notificacion que no es mia responde 404: el id no dice si existe.
 */
@Service
@RequiredArgsConstructor
class InboxQueryServiceImpl implements InboxQueryService {

    static final Set<String> SORTABLE = Set.of("createdAt", "severity", "category", "title");
    static final String AGGREGATE = "Notification";

    private final NotificationRepository notificationRepository;
    private final NotificationReceiptRepository receiptRepository;
    private final InboxStateRepository inboxStateRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserService currentUser;
    private final NotificationProperties properties;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InboxItemResponse> myInbox(InboxFilter filter, Pageable pageable) {
        Pageable sanitized = SortWhitelist.sanitize(pageable, SORTABLE, properties.inbox().maxPageSize());
        String username = username();
        List<String> keys = currentUser.getAudienceKeys();
        Instant allReadUntil = allReadUntil(username);

        Specification<Notification> spec = NotificationSpecification.visibleTo(keys)
                .and(NotificationSpecification.categoryEquals(filter.category()))
                .and(NotificationSpecification.severityEquals(filter.severity()))
                .and(NotificationSpecification.createdBetween(filter.from(), filter.to()));
        if (filter.unread() != null) {
            spec = spec.and(NotificationSpecification.unreadBy(username, allReadUntil, filter.unread()));
        }
        Page<Notification> page = notificationRepository.findAll(spec, sanitized);

        Map<UUID, Instant> receipts = receipts(username, page.getContent().stream().map(Notification::getId).toList());
        return PageMapper.toPageResponse(page, notification -> item(notification, receipts, allReadUntil));
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        String username = username();
        List<String> keys = currentUser.getAudienceKeys();
        int cap = properties.inbox().unreadCountCap();
        Specification<Notification> spec = NotificationSpecification.visibleTo(keys)
                .and(NotificationSpecification.unreadBy(username, allReadUntil(username), true));
        long found = notificationRepository.findBy(spec, query -> query.limit(cap + 1).all()).size();
        return found > cap ? new UnreadCountResponse(cap, true) : new UnreadCountResponse(found, false);
    }

    @Override
    @Transactional
    public InboxItemResponse markRead(UUID notificationId) {
        String username = username();
        List<String> keys = currentUser.getAudienceKeys();
        if (keys.isEmpty() || !notificationRepository.isVisibleTo(notificationId, keys)) {
            throw new NotFoundException(AGGREGATE, notificationId);
        }
        receiptRepository.insertIfMissing(notificationId, username);
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotFoundException(AGGREGATE, notificationId));
        return item(notification, receipts(username, List.of(notificationId)), allReadUntil(username));
    }

    @Override
    @Transactional
    public ReadAllResponse markAllRead() {
        String username = username();
        List<String> keys = currentUser.getAudienceKeys();
        // Hasta la mas reciente visible, no hasta ahora: una notificacion en vuelo con created_at
        // anterior podria confirmarse despues y nacer ya leida.
        Instant until = keys.isEmpty() ? null : notificationRepository.findLatestCreatedAtVisibleTo(keys);
        if (until == null) {
            return new ReadAllResponse(allReadUntil(username));
        }
        inboxStateRepository.markAllReadUntil(username, until);
        return new ReadAllResponse(allReadUntil(username));
    }

    private InboxItemResponse item(Notification notification, Map<UUID, Instant> receipts, Instant allReadUntil) {
        Instant readAt = receipts.get(notification.getId());
        boolean read = readAt != null || (allReadUntil != null && !notification.getCreatedAt().isAfter(allReadUntil));
        return notificationMapper.toInboxItem(notification, read, readAt);
    }

    private Map<UUID, Instant> receipts(String username, List<UUID> ids) {
        Map<UUID, Instant> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (NotificationReceipt receipt : receiptRepository.findByIdUsernameAndIdNotificationIdIn(username, ids)) {
            byId.put(receipt.getId().getNotificationId(), receipt.getReadAt());
        }
        return byId;
    }

    private Instant allReadUntil(String username) {
        return inboxStateRepository.findById(username).map(InboxState::getAllReadUntil).orElse(null);
    }

    private String username() {
        return currentUser.getUsername().orElseThrow(() -> new AccessDeniedException("The inbox needs an authenticated person"));
    }
}
