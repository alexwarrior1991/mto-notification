package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;

/**
 * Traduce lo que publica una fuente a lineas del registro. Uno por fuente de RabbitMQ, indexado
 * por {@link #sourceId()}; dos para la misma fuente impiden arrancar.
 *
 * <p>Corre dentro de la transaccion del inbox: lo que escriba se confirma con la marca de aplicado
 * o no se confirma nada, y no hace falta comprobar repeticiones. Lo que lance se propaga: un fallo
 * transitorio se reintenta; una {@code UnprocessableSourceEventException} va a la DLQ sin reintentos.</p>
 */
public interface ActivitySourceAdapter {

    /** La fuente que atiende: la clave de {@code app.rabbitmq.sources.*}. */
    String sourceId();

    void handle(SourceEnvelope envelope, SourceEventContext context);
}
