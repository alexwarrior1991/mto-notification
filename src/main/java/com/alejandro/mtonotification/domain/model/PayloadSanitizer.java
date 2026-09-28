package com.alejandro.mtonotification.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lo ultimo que ve un payload antes de guardarse. Los adaptadores ya construyen el suyo con lista
 * blanca; esto es la red por debajo: ninguna clave que huela a credencial entra en el registro,
 * venga de donde venga, y nada anidado o largo sin limite.
 */
public final class PayloadSanitizer {

    /** Fragmentos de nombre de clave que nunca se guardan, mire lo que mire el valor. */
    static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "credential", "otp", "authorization", "cookie", "apikey", "api_key");

    static final int MAX_DEPTH = 4;
    static final int MAX_ENTRIES = 64;
    static final int MAX_STRING_LENGTH = 2000;

    private PayloadSanitizer() {
    }

    public static boolean isForbiddenKey(String key) {
        if (key == null) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return FORBIDDEN_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    public static Map<String, Object> sanitize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(sanitizeMap(payload, 1));
    }

    private static Map<String, Object> sanitizeMap(Map<?, ?> source, int depth) {
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (clean.size() >= MAX_ENTRIES) {
                break;
            }
            String key = entry.getKey() == null ? null : entry.getKey().toString();
            if (key == null || key.isBlank() || isForbiddenKey(key)) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), depth);
            if (value != null) {
                clean.put(key, value);
            }
        }
        return clean;
    }

    private static Object sanitizeValue(Object value, int depth) {
        return switch (value) {
            case null -> null;
            case String text -> DomainValidations.truncate(text, MAX_STRING_LENGTH);
            case Number number -> number;
            case Boolean flag -> flag;
            case Enum<?> constant -> constant.name();
            case Map<?, ?> map -> depth >= MAX_DEPTH ? null : sanitizeMap(map, depth + 1);
            case List<?> list -> depth >= MAX_DEPTH ? null : list.stream()
                    .limit(MAX_ENTRIES)
                    .map(item -> sanitizeValue(item, depth + 1))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            default -> DomainValidations.truncate(value.toString(), MAX_STRING_LENGTH);
        };
    }
}
