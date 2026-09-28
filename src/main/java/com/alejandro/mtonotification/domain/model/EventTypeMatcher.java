package com.alejandro.mtonotification.domain.model;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lo que una regla dice de {@code event}: un tipo exacto, una lista de tipos o un patron con
 * comodin final ({@code maintenance.order.*}, que casa con todo lo que empiece por
 * {@code maintenance.order.}). No hay mas sintaxis a proposito.
 */
public record EventTypeMatcher(Set<String> exact, Set<String> prefixes) {

    private static final String WILDCARD = ".*";

    public EventTypeMatcher {
        exact = Set.copyOf(exact == null ? Set.of() : exact);
        prefixes = Set.copyOf(prefixes == null ? Set.of() : prefixes);
        if (exact.isEmpty() && prefixes.isEmpty()) {
            throw new IllegalArgumentException("An event matcher needs at least one type or pattern");
        }
    }

    public static EventTypeMatcher of(List<String> patterns) {
        Set<String> exact = new TreeSet<>();
        Set<String> prefixes = new TreeSet<>();
        for (String raw : patterns == null ? List.<String>of() : patterns) {
            String pattern = DomainValidations.requireNonBlank(raw, "event pattern").trim();
            if (pattern.endsWith(WILDCARD)) {
                String prefix = pattern.substring(0, pattern.length() - WILDCARD.length());
                if (!ActivityTypes.isWellFormed(prefix) && !isSingleSegment(prefix)) {
                    throw new IllegalArgumentException("Event pattern '" + pattern + "' is not <category>[.<subject>].*");
                }
                prefixes.add(prefix + ".");
            } else {
                if (!ActivityTypes.isKnown(pattern)) {
                    throw new IllegalArgumentException("Event type '" + pattern + "' is not in the catalogue of activity types");
                }
                exact.add(pattern);
            }
        }
        return new EventTypeMatcher(exact, prefixes);
    }

    public boolean matches(String type) {
        if (type == null) {
            return false;
        }
        if (exact.contains(type)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Lo que se ensena en la administracion. */
    public List<String> describe() {
        List<String> described = new java.util.ArrayList<>(exact);
        prefixes.forEach(prefix -> described.add(prefix + "*"));
        return List.copyOf(described);
    }

    private static boolean isSingleSegment(String prefix) {
        return prefix.matches("^[a-z0-9-]+$") && ActivityTypes.categoryOf(prefix + ".x").isPresent();
    }
}
