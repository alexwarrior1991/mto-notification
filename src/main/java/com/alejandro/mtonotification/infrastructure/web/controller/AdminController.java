package com.alejandro.mtonotification.infrastructure.web.controller;

import com.alejandro.mtonotification.application.dto.admin.BroadcastRequest;
import com.alejandro.mtonotification.application.dto.admin.RulesResponse;
import com.alejandro.mtonotification.application.dto.admin.SourceStatusResponse;
import com.alejandro.mtonotification.application.dto.admin.TestEmailRequest;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryFilter;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryResponse;
import com.alejandro.mtonotification.application.dto.notification.NotificationResponse;
import com.alejandro.mtonotification.application.service.AdminService;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.web.NotificationApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/** Administracion. Pide {@code notification-admin}. */
@RestController
@RequestMapping(NotificationApiPaths.BASE + NotificationApiPaths.ADMIN)
@RequiredArgsConstructor
@Tag(name = "Administration", description = "Loaded rules, deliveries and retries, source status, test email and manual broadcasts")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/rules")
    @Operation(summary = "The loaded rules and where they came from")
    public RulesResponse rules() {
        return adminService.rules();
    }

    @GetMapping("/deliveries")
    @Operation(summary = "Deliveries by the pushing channels", description = "sort admits createdAt, status, nextAttemptAt, channel and sentAt.")
    public PageResponse<DeliveryResponse> deliveries(
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) UUID notificationId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminService.deliveries(new DeliveryFilter(status, channel, notificationId), pageable);
    }

    @PostMapping("/deliveries/{id}/retry")
    @Operation(summary = "Queue a FAILED or SKIPPED delivery again", description = "404 DLV-404; 409 DLV-409 when its status does not admit a retry.")
    public DeliveryResponse retry(@PathVariable UUID id) {
        return adminService.retry(id);
    }

    @GetMapping("/sources")
    @Operation(summary = "State of the sources", description = "Watermarks and leases of the Keycloak reader, inbox counts per source, open bursts and deliveries per status.")
    public SourceStatusResponse sources() {
        return adminService.sources();
    }

    @PostMapping("/test-email")
    @Operation(summary = "Send a test email", description = "Creates a system.test-email notification for the caller with one direct delivery to the address. 202: the delivery happens asynchronously.")
    public ResponseEntity<NotificationResponse> testEmail(@Valid @RequestBody TestEmailRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(adminService.sendTestEmail(request));
    }

    @PostMapping("/broadcasts")
    @Operation(summary = "Send a manual notification", description = "Audiences as KIND:key. 422 NTF-422 with an unknown audience kind or channel.")
    public ResponseEntity<NotificationResponse> broadcast(@Valid @RequestBody BroadcastRequest request) {
        NotificationResponse created = adminService.broadcast(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(NotificationApiPaths.BASE + NotificationApiPaths.ADMIN + "/broadcasts/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }
}
