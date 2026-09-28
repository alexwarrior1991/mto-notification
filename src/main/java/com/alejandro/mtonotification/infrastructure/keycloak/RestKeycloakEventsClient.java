package com.alejandro.mtonotification.infrastructure.keycloak;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.application.service.KeycloakEventsClient;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Los eventos del realm. Se pide el JSON como texto y se recorre nodo a nodo, para guardar en el
 * inbox cada evento tal como llego y no una reserializacion del DTO.
 */
public class RestKeycloakEventsClient implements KeycloakEventsClient {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final CircuitBreaker circuitBreaker;

    public RestKeycloakEventsClient(RestClient restClient, JsonMapper jsonMapper, CircuitBreaker circuitBreaker) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public List<KeycloakEvent> loginEvents(int first, int max) {
        String body = KeycloakApiSupport.call(circuitBreaker, "GET /events", () -> restClient.get()
                .uri(KeycloakAdminApi.EVENTS + "?first={first}&max={max}", first, max)
                .retrieve()
                .body(String.class));
        return parse(body, node -> jsonMapper.treeToValue(node, KeycloakEvent.class).withRaw(node.toString()));
    }

    @Override
    public List<KeycloakAdminEvent> adminEvents(int first, int max) {
        String body = KeycloakApiSupport.call(circuitBreaker, "GET /admin-events", () -> restClient.get()
                .uri(KeycloakAdminApi.ADMIN_EVENTS + "?first={first}&max={max}", first, max)
                .retrieve()
                .body(String.class));
        return parse(body, node -> jsonMapper.treeToValue(node, KeycloakAdminEvent.class).withRaw(node.toString()));
    }

    private <T> List<T> parse(String body, Function<JsonNode, T> reader) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode array = jsonMapper.readTree(body);
        if (!array.isArray()) {
            return List.of();
        }
        List<T> items = new ArrayList<>();
        for (JsonNode node : array) {
            items.add(reader.apply(node));
        }
        return items;
    }
}
