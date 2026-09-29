package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;

import java.util.Map;

/**
 * El lector de Keycloak: por cada fuente, un arrendamiento, paginas de lo mas nuevo hacia atras
 * hasta cruzar la marca (menos el solape), cada evento por el inbox con su huella, y la marca solo
 * avanza al terminar la pasada. HTTP siempre fuera de transaccion.
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
     * @param failed     eventos que fallaron y quedaron FAILED en el inbox
     * @param error      el fallo de la fuente, o {@code null}
     */
    record PollSummary(boolean leased, int fetched, int ingested, int duplicates, int failed, String error) {

        public static PollSummary notLeased() {
            return new PollSummary(false, 0, 0, 0, 0, null);
        }
    }
}
