package com.alejandro.mtonotification.application.dto.messaging;

/**
 * Un mensaje que quedo FAILED en el inbox y al que le toca otro intento: lo que hace falta para
 * volver a aplicarlo desde su JSON guardado, sin volver a pedirlo a su fuente.
 *
 * @param attempts intentos fallidos hasta ahora
 */
public record FailedInboxMessage(
        String messageId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payloadHash,
        String payload,
        int attempts
) {
}
