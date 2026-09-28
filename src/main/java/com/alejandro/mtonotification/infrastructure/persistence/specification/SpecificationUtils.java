package com.alejandro.mtonotification.infrastructure.persistence.specification;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/** Lo que comparten los filtros: un valor ausente no filtra. */
final class SpecificationUtils {

    private SpecificationUtils() {
    }

    static <T> Specification<T> alwaysTrue() {
        return (root, query, builder) -> builder.conjunction();
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static <T> Specification<T> equalsValue(String attribute, Object value) {
        if (value == null) {
            return alwaysTrue();
        }
        return (root, query, builder) -> builder.equal(root.get(attribute), value);
    }

    static <T> Specification<T> equalsIgnoreCase(String attribute, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return alwaysTrue();
        }
        return (root, query, builder) -> builder.equal(builder.lower(root.get(attribute)), normalized.toLowerCase());
    }

    static <T> Specification<T> between(String attribute, Instant from, Instant to) {
        if (from == null && to == null) {
            return alwaysTrue();
        }
        return (root, query, builder) -> {
            if (from != null && to != null) {
                return builder.between(root.get(attribute), from, to);
            }
            return from != null
                    ? builder.greaterThanOrEqualTo(root.get(attribute), from)
                    : builder.lessThanOrEqualTo(root.get(attribute), to);
        };
    }
}
