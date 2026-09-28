package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;

/**
 * Ejecuta un trabajo como mucho una vez por mensaje, llegue como llegue (RabbitMQ o un sondeo).
 * La idempotencia la garantiza la restriccion unica de {@code inbox_message}, no esta interfaz.
 */
public interface InboxMessageService {

    /**
     * Registra el mensaje y ejecuta {@code processing} solo si no se habia aplicado ya, todo en una
     * transaccion: si el trabajo lanza, revierte entero y no queda un mensaje aplicado cuyo efecto
     * se deshizo.
     */
    InboxProcessingResult process(InboxMessageCommand command, Runnable processing);

    /**
     * Deja el mensaje como fallido, en su propia transaccion. Se llama DESPUES de que
     * {@link #process} haya terminado, nunca desde dentro.
     */
    void recordFailure(InboxMessageCommand command, Throwable failure);
}
