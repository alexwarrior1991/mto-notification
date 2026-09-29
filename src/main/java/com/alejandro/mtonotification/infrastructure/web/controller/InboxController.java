package com.alejandro.mtonotification.infrastructure.web.controller;

import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.inbox.InboxFilter;
import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.inbox.ReadAllResponse;
import com.alejandro.mtonotification.application.dto.inbox.UnreadCountResponse;
import com.alejandro.mtonotification.application.service.InboxQueryService;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.infrastructure.web.NotificationApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Mi bandeja. Todo pide {@code notification-inbox}: leer y marcar son el estado propio de la persona. */
@RestController
@RequestMapping(NotificationApiPaths.BASE + NotificationApiPaths.INBOX)
@RequiredArgsConstructor
@Tag(name = "Inbox", description = "Notifications addressed to the caller (by user, profile or client role) with read state per person")
public class InboxController {

    private final InboxQueryService inboxQueryService;

    @GetMapping
    @Operation(summary = "My notifications", description = "Newest first. unread=true lists only the unread ones; sort admits createdAt, severity, category and title.")
    public PageResponse<InboxItemResponse> myInbox(
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) ActivityCategory category,
            @RequestParam(required = false) ActivitySeverity severity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return inboxQueryService.myInbox(new InboxFilter(unread, category, severity, from, to), pageable);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread count for the bell", description = "Capped: with capped=true the count is 'cap or more', not a total.")
    public UnreadCountResponse unreadCount() {
        return inboxQueryService.unreadCount();
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one of my notifications as read", description = "404 NTF-404 when it is not addressed to me: the id does not tell whether it exists.")
    public InboxItemResponse markRead(@PathVariable UUID id) {
        return inboxQueryService.markRead(id);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark everything visible as read", description = "Up to the newest visible notification, not up to now.")
    public ReadAllResponse markAllRead() {
        return inboxQueryService.markAllRead();
    }
}
