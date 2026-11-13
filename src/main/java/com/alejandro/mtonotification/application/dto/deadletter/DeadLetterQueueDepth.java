package com.alejandro.mtonotification.application.dto.deadletter;

/**
 * Cuantos mensajes guarda la DLQ de una fuente. {@code messages} es {@code null} si la cola no
 * existe en el broker (un despliegue que aun no la ha declarado).
 */
public record DeadLetterQueueDepth(String source, String queue, String deadLetterQueue, Long messages) {
}
