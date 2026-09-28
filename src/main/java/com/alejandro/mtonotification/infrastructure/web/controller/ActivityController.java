package com.alejandro.mtonotification.infrastructure.web.controller;

import com.alejandro.mtonotification.application.dto.activity.ActivityEventFilter;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventResponse;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.service.ActivityQueryService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** El registro de actividad, todo menos los accesos. Pide {@code notification-activity-read}. */
@RestController
@RequestMapping(NotificationApiPaths.BASE + NotificationApiPaths.ACTIVITY)
@RequiredArgsConstructor
@Tag(name = "Activity", description = "The activity log of the domain: what, who, when, from which service, on which entity. Access events are served by /access.")
public class ActivityController {

    private final ActivityQueryService activityQueryService;

    @GetMapping
    @Operation(summary = "Search the log", description = "category=ACCESS is a 400: accesses have their own endpoint and permission. Superseded events are hidden unless includeSuperseded=true. sort admits occurredAt, recordedAt, severity, type and seq.")
    public PageResponse<ActivityEventResponse> search(
            @RequestParam(required = false) ActivityCategory category,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String actorUsername,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) ActivitySeverity severity,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false, defaultValue = "false") boolean includeSuperseded,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return activityQueryService.search(new ActivityEventFilter(category, type, actorUsername, subjectType, subjectId,
                severity, sourceService, from, to, includeSuperseded), pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One event with its payload", description = "404 ACT-404 if it does not exist or is an access event.")
    public ActivityEventResponse get(@PathVariable UUID id) {
        return activityQueryService.get(id);
    }
}
