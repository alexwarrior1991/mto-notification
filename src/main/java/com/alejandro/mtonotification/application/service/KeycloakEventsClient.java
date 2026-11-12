package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;

import java.util.List;

/**
 * Los eventos del realm por la Admin API, con la cuenta de servicio. Keycloak (26.1) los devuelve
 * siempre del mas reciente al mas antiguo, paginados con {@code first}/{@code max} a cualquier
 * profundidad; no hay orden ascendente y la fecha solo filtra por dia, asi que la marca de agua se
 * cruza paginando, y el lector busca a que profundidad esta con peticiones de un evento
 * ({@code max = 1}).
 *
 * <p>Lo que lance es un fallo de la fuente (red, circuito abierto, 401 de la cuenta de servicio) y
 * el lector lo apunta en la marca sin avanzarla.</p>
 */
public interface KeycloakEventsClient {

    List<KeycloakEvent> loginEvents(int first, int max);

    List<KeycloakAdminEvent> adminEvents(int first, int max);
}
