package com.alejandro.mtonotification.application.dto.admin;

import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterQueueDepth;

import java.util.List;

/**
 * Las DLQ de las fuentes con lo que guarda cada una. Con el broker apagado
 * ({@code app.rabbitmq.enabled=false}) {@code enabled} es {@code false} y no hay colas que contar.
 */
public record DeadLetterQueuesResponse(boolean enabled, List<DeadLetterQueueDepth> queues) {
}
