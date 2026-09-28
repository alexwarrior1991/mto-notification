package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.access.AccessEventFilter;
import com.alejandro.mtonotification.application.dto.access.AccessEventResponse;
import com.alejandro.mtonotification.application.dto.access.AccessOutcome;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventFilter;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventResponse;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.ValidationException;
import com.alejandro.mtonotification.application.mapper.ActivityEventMapper;
import com.alejandro.mtonotification.application.mapper.PageMapper;
import com.alejandro.mtonotification.application.service.ActivityQueryService;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import com.alejandro.mtonotification.infrastructure.persistence.specification.ActivityEventSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** El registro por una puerta y los accesos por otra: un acceso nunca sale por {@code /activity}. */
@Service
@RequiredArgsConstructor
class ActivityQueryServiceImpl implements ActivityQueryService {

    static final Set<String> SORTABLE = Set.of("occurredAt", "recordedAt", "severity", "type", "seq");
    static final String AGGREGATE = "Activity event";

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final ActivityEventRepository repository;
    private final ActivityEventMapper mapper;
    private final NotificationProperties properties;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ActivityEventResponse> search(ActivityEventFilter filter, Pageable pageable) {
        if (filter.category() == ActivityCategory.ACCESS) {
            throw new ValidationException("Access events are served by /access, with their own permission");
        }
        Pageable sanitized = SortWhitelist.sanitize(pageable, SORTABLE, properties.inbox().maxPageSize());
        Specification<ActivityEvent> spec = ActivityEventSpecification.categoryNot(ActivityCategory.ACCESS)
                .and(ActivityEventSpecification.categoryEquals(filter.category()))
                .and(ActivityEventSpecification.typeEquals(filter.type()))
                .and(ActivityEventSpecification.actorUsernameEquals(filter.actorUsername()))
                .and(ActivityEventSpecification.subjectTypeEquals(filter.subjectType()))
                .and(ActivityEventSpecification.subjectIdEquals(filter.subjectId()))
                .and(ActivityEventSpecification.severityEquals(filter.severity()))
                .and(ActivityEventSpecification.sourceServiceEquals(filter.sourceService()))
                .and(ActivityEventSpecification.occurredBetween(filter.from(), filter.to()));
        if (!filter.includeSuperseded()) {
            spec = spec.and(ActivityEventSpecification.notSuperseded());
        }
        return PageMapper.toPageResponse(repository.findAll(spec, sanitized), mapper::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityEventResponse get(UUID id) {
        ActivityEvent event = repository.findById(id)
                .filter(found -> found.getCategory() != ActivityCategory.ACCESS)
                .orElseThrow(() -> new NotFoundException(AGGREGATE, id));
        return mapper.toDetail(event);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AccessEventResponse> searchAccess(AccessEventFilter filter, Pageable pageable) {
        Pageable sanitized = SortWhitelist.sanitize(pageable, SORTABLE, properties.inbox().maxPageSize());
        Specification<ActivityEvent> spec = ActivityEventSpecification.categoryEquals(ActivityCategory.ACCESS)
                .and(ActivityEventSpecification.actorUsernameEquals(filter.username()))
                .and(ActivityEventSpecification.ipAddressEquals(parseIp(filter.ipAddress())))
                .and(ActivityEventSpecification.typeEquals(filter.type()))
                .and(ActivityEventSpecification.occurredBetween(filter.from(), filter.to()));
        if (filter.outcome() == AccessOutcome.FAILURE) {
            spec = spec.and(ActivityEventSpecification.typeIn(ActivityEventMapper.FAILURE_TYPES));
        } else if (filter.outcome() == AccessOutcome.SUCCESS) {
            spec = spec.and(ActivityEventSpecification.typeNotIn(ActivityEventMapper.FAILURE_TYPES));
        }
        return PageMapper.toPageResponse(repository.findAll(spec, sanitized), mapper::toAccess);
    }

    /** Solo literales: un nombre de host haria una consulta DNS desde un parametro de la API. */
    static InetAddress parseIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String literal = value.trim();
        if (!IP_LITERAL.matcher(literal).matches()) {
            throw new ValidationException("ipAddress must be an IPv4 or IPv6 literal");
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException invalid) {
            throw new ValidationException("ipAddress must be an IPv4 or IPv6 literal");
        }
    }
}
