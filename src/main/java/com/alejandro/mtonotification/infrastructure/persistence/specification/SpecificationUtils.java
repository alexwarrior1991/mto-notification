package com.alejandro.mtonotification.infrastructure.persistence.specification;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;

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

    /**
     * Para una columna que SIEMPRE se guarda en minusculas: se compara la columna tal cual con el
     * parametro en minusculas, que da lo mismo que {@code lower(col) = ?} y, a diferencia de el, deja
     * usar el indice de la columna. Con {@link #equalsIgnoreCase} el indice no sirve.
     */
    static <T> Specification<T> equalsLowerCased(String attribute, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return alwaysTrue();
        }
        return (root, query, builder) -> builder.equal(root.get(attribute), normalized.toLowerCase(Locale.ROOT));
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
