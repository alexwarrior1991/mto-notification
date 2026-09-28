package com.alejandro.mtonotification.application.dto.messaging;

/**
 * Todo lo que el inbox necesita para registrar un mensaje, ya sin tipos de AMQP. Lo construye la
 * infraestructura (el consumidor de RabbitMQ o el lector de Keycloak); el inbox no conoce el transporte.
 *
 * @param messageId      clave de idempotencia; obligatoria y nunca truncada
 * @param sourceService  emisor; forma parte de la clave unica
 * @param eventType      tipo de evento, si viaja
 * @param aggregateType  entidad publicada
 * @param aggregateId    identificador de la entidad publicada
 * @param exchangeName   exchange por el que llego (o {@code null} en un sondeo)
 * @param routingKey     routing key con la que llego
 * @param queueName      cola de la que se consumio
 * @param payloadHash    huella del payload; auxiliar, nunca la clave
 * @param payload        JSON original, sin reserializar
 * @param sequenceNumber numero de secuencia del emisor, o {@code null}
 */
public record InboxMessageCommand(
        String messageId,
        String sourceService,
        String eventType,
        String aggregateType,
        String aggregateId,
        String exchangeName,
        String routingKey,
        String queueName,
        String payloadHash,
        String payload,
        Long sequenceNumber
) {
}
