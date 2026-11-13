package com.alejandro.mtonotification.application.dto.deadletter;

import java.time.Instant;

/**
 * Un mensaje de una DLQ, mirado sin consumirlo: de donde venia, por que lo descarto el broker y,
 * si no llegaria al inbox, por que. Sin el cuerpo, a proposito: el JSON crudo solo vive en el inbox,
 * y un mensaje que se descarto por su firma puede traer cualquier cosa.
 *
 * @param messageId     la clave del inbox ({@code operationId} del sobre o el {@code message_id} de
 *                      AMQP); {@code null} si el mensaje no trae ninguna
 * @param sourceService el {@code origin} del sobre, la otra mitad de la clave del inbox
 * @param exchange      el exchange por el que llego a la cola de trabajo (de {@code x-death})
 * @param routingKey    la clave de enrutado original (de {@code x-death})
 * @param deathReason   por que lo descarto el broker: {@code rejected}, {@code delivery_limit}...
 * @param deathCount    cuantas veces ha muerto en esa cola
 * @param problem       lo que el consumidor rechazaria antes del inbox (la firma, un cuerpo que no
 *                      es el sobre, sin {@code data}, sin identificador); {@code null} si llegaria
 */
public record DeadLetter(
        String messageId,
        String sourceService,
        String eventType,
        String aggregateType,
        String aggregateId,
        String exchange,
        String routingKey,
        String deathReason,
        Long deathCount,
        Instant deadLetteredAt,
        int payloadBytes,
        String problem
) {
}
