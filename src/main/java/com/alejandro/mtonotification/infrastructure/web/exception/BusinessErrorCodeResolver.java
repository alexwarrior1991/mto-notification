package com.alejandro.mtonotification.infrastructure.web.exception;

import com.alejandro.mtonotification.application.exception.BusinessException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.ValidationException;

import java.util.Map;

/**
 * Codigo de error estable por tipo de excepcion, para que un cliente distinga la causa sin
 * analizar el mensaje. El prefijo sale del agregado en los 404.
 */
final class BusinessErrorCodeResolver {

    private static final Map<String, String> AGGREGATE_PREFIXES = Map.ofEntries(
            Map.entry("Notification", "NTF"),
            Map.entry("ActivityEvent", "ACT"),
            Map.entry("Activity event", "ACT"),
            Map.entry("Delivery", "DLV"),
            Map.entry("Rule", "RUL"),
            Map.entry("Source", "SRC")
    );

    private BusinessErrorCodeResolver() {
    }

    static String resolve(BusinessException exception) {
        return switch (exception) {
            case NotFoundException notFound -> aggregateCode(notFound.getAggregate(), "404", "APP-404");
            case ValidationException ignored -> "VAL-001";
            default -> "BUS-001";
        };
    }

    private static String aggregateCode(String aggregate, String suffix, String fallback) {
        String prefix = AGGREGATE_PREFIXES.get(aggregate);
        return prefix == null ? fallback : "%s-%s".formatted(prefix, suffix);
    }
}
