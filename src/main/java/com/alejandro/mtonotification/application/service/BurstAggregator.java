package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.domain.model.Actor;

import java.time.Instant;

/**
 * Las rafagas de datos maestros: cada evento agregado hace un upsert en la rafaga abierta de su
 * clave, y el planificador cierra las que llevan un rato ociosas y escribe UNA linea con el recuento.
 */
public interface BurstAggregator {

    void record(String sourceService, String entityName, String operation, Actor actor, String correlationId,
                String sampleId, Instant eventAt);

    /** Cierra lo que toque y escribe sus lineas; devuelve cuantas cerro. */
    int closeExpired();
}
