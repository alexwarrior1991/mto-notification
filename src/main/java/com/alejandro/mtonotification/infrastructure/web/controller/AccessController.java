package com.alejandro.mtonotification.infrastructure.web.controller;

import com.alejandro.mtonotification.application.dto.access.AccessEventFilter;
import com.alejandro.mtonotification.application.dto.access.AccessEventResponse;
import com.alejandro.mtonotification.application.dto.access.AccessOutcome;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.service.ActivityQueryService;
import com.alejandro.mtonotification.infrastructure.web.NotificationApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Los accesos: usuario e IP. Pide {@code notification-access-read}, que no viene con el registro. */
@RestController
@RequestMapping(NotificationApiPaths.BASE + NotificationApiPaths.ACCESS)
@RequiredArgsConstructor
@Tag(name = "Access", description = "Logins, failures, logouts, lockouts and credential changes, with username and IP")
public class AccessController {

    private final ActivityQueryService activityQueryService;

    @GetMapping
    @Operation(summary = "Search the accesses", description = "outcome=FAILURE keeps failed logins, streaks, lockouts and failed logouts; SUCCESS the rest. ipAddress must be a literal.")
    public PageResponse<AccessEventResponse> search(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) AccessOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return activityQueryService.searchAccess(new AccessEventFilter(username, ipAddress, type, outcome, from, to), pageable);
    }
}
