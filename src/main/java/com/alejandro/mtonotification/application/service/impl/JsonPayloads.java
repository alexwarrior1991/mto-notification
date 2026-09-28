package com.alejandro.mtonotification.application.service.impl;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * El JSON de las columnas {@code jsonb} va y viene como texto: aqui se traduce a mapa con el
 * {@code JsonMapper} de la aplicacion, para no mantener dos configuraciones de Jackson.
 */
@Component
public class JsonPayloads {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;

    public JsonPayloads(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(payload == null ? Map.of() : payload);
    }

    public Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> read = jsonMapper.readValue(json, MAP);
            return read == null ? Map.of() : read;
        } catch (RuntimeException unreadable) {
            // Una columna que no es un objeto JSON (no deberia pasar) no puede tumbar una lectura.
            return Map.of("_raw", json);
        }
    }

    public String writeList(java.util.List<?> values) {
        return jsonMapper.writeValueAsString(values == null ? java.util.List.of() : values);
    }

    public java.util.List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return java.util.List.of();
        }
        try {
            java.util.List<String> read = jsonMapper.readValue(json, new TypeReference<java.util.List<String>>() {
            });
            return read == null ? java.util.List.of() : read;
        } catch (RuntimeException unreadable) {
            return java.util.List.of();
        }
    }
}
