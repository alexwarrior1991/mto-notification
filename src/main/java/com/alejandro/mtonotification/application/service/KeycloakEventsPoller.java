package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;

import java.util.Map;

/**
 * El lector de Keycloak: por cada fuente, un arrendamiento, los fallidos del inbox a los que les toca
 * otro intento y los eventos desde la marca (menos el solape), del mas antiguo al mas nuevo, cada uno
 * por el inbox con su huella. Con mas eventos pendientes de los que caben en un tramo, lee por tramos
 * desde el mas antiguo y la marca avanza solo sobre lo ya procesado: ninguno se salta. HTTP siempre
 * fuera de transaccion.
 */
public interface KeycloakEventsPoller {

    /** Una pasada por las fuentes activas. */
    Map<SourceKind, PollSummary> pollOnce();

    PollSummary poll(SourceKind kind);

    /**
     * @param leased     si esta instancia consiguio la fuente
     * @param fetched    eventos leidos dentro de la ventana
     * @param ingested   lineas nuevas del registro
     * @param duplicates eventos que el inbox ya tenia
     * @param failed     eventos que fallaron y quedaron FAILED en el inbox (los nuevos y los reintentos que volvieron a fallar)
     * @param recovered  fallidos de pasadas anteriores que esta vez entraron
     * @param error      el fallo de la fuente, o {@code null}
     */
    record PollSummary(boolean leased, int fetched, int ingested, int duplicates, int failed, int recovered, String error) {

        public static PollSummary notLeased() {
            return new PollSummary(false, 0, 0, 0, 0, 0, null);
        }
    }
}
