package com.alejandro.mtonotification.application.service.impl;

import java.util.List;
import java.util.Map;

/**
 * Lectura tolerante de un mapa abierto (el {@code data} o los {@code values} de un sobre, los
 * {@code details} de Keycloak): una clave ausente o de tipo raro se lee como {@code null}. El
 * emisor puede anadir o quitar claves sin avisar, y un evento legitimo no va a la DLQ por una
 * clave que aqui no se usa.
 */
final class PayloadReader {

    private final Map<String, Object> values;

    private PayloadReader(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    static PayloadReader of(Map<String, Object> values) {
        return new PayloadReader(values);
    }

    String string(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    Long longValue(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    boolean has(String key) {
        return values.get(key) != null;
    }

    @SuppressWarnings("unchecked")
    PayloadReader nested(String key) {
        Object value = values.get(key);
        return value instanceof Map<?, ?> map ? new PayloadReader((Map<String, Object>) map) : new PayloadReader(Map.of());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String key) {
        Object value = values.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    List<String> stringList(String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    Map<String, Object> all() {
        return values;
    }
}
