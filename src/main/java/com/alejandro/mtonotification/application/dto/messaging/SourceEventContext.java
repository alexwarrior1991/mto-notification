package com.alejandro.mtonotification.application.dto.messaging;

/**
 * Lo que viaja fuera del payload y un adaptador puede necesitar.
 *
 * @param sourceId       la fuente por la que entro ({@code master-data}, {@code maintenance}...)
 * @param sequenceNumber numero de secuencia del emisor, o {@code null}
 * @param routingKey     la routing key con la que llego, o {@code null}
 */
public record SourceEventContext(String sourceId, Long sequenceNumber, String routingKey) {
}
