package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.NotificationFactory;
import com.alejandro.mtonotification.application.service.RuleEngine;
import com.alejandro.mtonotification.application.service.RuleRepository;
import com.alejandro.mtonotification.application.service.ThrottleGate;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.domain.model.AudienceKind;
import com.alejandro.mtonotification.domain.model.DomainValidations;
import com.alejandro.mtonotification.domain.model.NotificationRule;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Casa cada linea con las reglas: tipo, condicion, freno, audiencias, textos. Una regla que falla
 * al evaluarse se salta con un aviso en el log y no tumba la ingesta: el registro vale mas que el
 * aviso, y una plantilla rota se ve en el log y en la administracion.
 */
@Service
@RequiredArgsConstructor
class RuleEngineImpl implements RuleEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleEngineImpl.class);

    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_LINK_LENGTH = 500;

    private final RuleRepository ruleRepository;
    private final RuleExpressionEvaluator evaluator;
    private final ThrottleGate throttleGate;
    private final NotificationFactory notificationFactory;

    @Override
    public List<Notification> evaluate(ActivityEvent event, Map<String, Object> payload) {
        Map<String, Object> scope = scope(event, payload);
        scope.put(RuleExpressionEvaluator.VARS, ruleRepository.variables());
        List<Notification> created = new ArrayList<>();
        for (NotificationRule rule : ruleRepository.rules()) {
            if (!rule.matcher().matches(event.getType())) {
                continue;
            }
            try {
                apply(rule, event, scope).ifPresent(created::add);
            } catch (RuntimeException failure) {
                LOGGER.warn("Rule '{}' could not be evaluated for event {} ({}): {}", rule.key(), event.getId(),
                        event.getType(), failure.getMessage());
            }
        }
        return created;
    }

    private Optional<Notification> apply(NotificationRule rule, ActivityEvent event, Map<String, Object> scope) {
        if (rule.when() != null && !evaluator.condition(rule.when(), scope)) {
            return Optional.empty();
        }
        if (rule.throttle() != null) {
            String dimension = rule.throttle().key() == null ? "*" : evaluator.render(rule.throttle().key(), scope);
            if (!throttleGate.tryAcquire(rule.key(), dimension, rule.throttle().window())) {
                LOGGER.info("Rule '{}' throttled for '{}' (window {})", rule.key(), dimension, rule.throttle().window());
                return Optional.empty();
            }
        }
        List<Audience> audiences = audiences(rule, scope);
        if (audiences.isEmpty()) {
            LOGGER.warn("Rule '{}' matched event {} but every audience rendered blank; nothing sent", rule.key(), event.getId());
            return Optional.empty();
        }
        ActivitySeverity severity = rule.severity() == null ? event.getSeverity() : rule.severity();
        String title = DomainValidations.truncate(blankToNull(evaluator.render(rule.title(), scope)), MAX_TITLE_LENGTH);
        if (title == null) {
            title = event.getType();
        }
        String body = blankToNull(evaluator.render(rule.body(), scope));
        String link = DomainValidations.truncate(blankToNull(evaluator.render(rule.link(), scope)), MAX_LINK_LENGTH);

        Notification notification = notificationFactory.create(new NotificationFactory.NotificationDraft(
                rule.key(), event.getId(), event.getCategory(), severity, title, body, link,
                event.getSubjectType(), event.getSubjectId(), audiences, rule.channels()));
        LOGGER.info("Rule '{}' created notification {} for event {} to {}", rule.key(), notification.getId(), event.getId(),
                audiences.stream().map(Audience::toKey).toList());
        return Optional.of(notification);
    }

    /** Las audiencias renderizadas; la clase es literal y la clave, plantilla. Una clave en blanco se omite. */
    private List<Audience> audiences(NotificationRule rule, Map<String, Object> scope) {
        Set<Audience> audiences = new LinkedHashSet<>();
        for (String template : rule.audiences()) {
            int separator = template.indexOf(':');
            AudienceKind kind = AudienceKind.parse(template.substring(0, separator)).orElseThrow();
            String key = blankToNull(evaluator.render(template.substring(separator + 1), scope));
            if (key != null) {
                audiences.add(new Audience(kind, key));
            }
        }
        return List.copyOf(audiences);
    }

    static Map<String, Object> scope(ActivityEvent event, Map<String, Object> payload) {
        Map<String, Object> eventMap = new LinkedHashMap<>();
        eventMap.put("id", event.getId() == null ? null : event.getId().toString());
        eventMap.put("type", event.getType());
        eventMap.put("category", event.getCategory() == null ? null : event.getCategory().name());
        eventMap.put("severity", event.getSeverity() == null ? null : event.getSeverity().name());
        eventMap.put("occurredAt", event.getOccurredAt() == null ? null : event.getOccurredAt().toString());
        eventMap.put("actorKind", event.getActorKind() == null ? null : event.getActorKind().name());
        eventMap.put("actorUsername", event.getActorUsername());
        eventMap.put("actorId", event.getActorId());
        eventMap.put("subjectType", event.getSubjectType());
        eventMap.put("subjectId", event.getSubjectId());
        eventMap.put("subjectLabel", event.getSubjectLabel());
        eventMap.put("correlationId", event.getCorrelationId());
        eventMap.put("eventCount", event.getEventCount());
        eventMap.put("sourceService", event.getSourceService());
        eventMap.put("ipAddress", event.getIpAddress() == null ? null : event.getIpAddress().getHostAddress());

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put(RuleExpressionEvaluator.EVENT, eventMap);
        scope.put(RuleExpressionEvaluator.PAYLOAD, payload == null ? Map.of() : payload);
        return scope;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
