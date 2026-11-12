package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.messaging.FailedInboxMessage;
import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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

    /** De {@code messageIds}, los que estan FAILED en el inbox de esa fuente. */
    Set<String> failedAmong(String sourceService, Collection<String> messageIds);

    /**
     * Los FAILED de una fuente a los que les toca otro intento: menos de {@code maxAttempts} fallos y
     * pasada su espera ({@code backoff} tras el primero, el doble tras cada uno, hasta {@code maxBackoff}).
     */
    List<FailedInboxMessage> failedDueForRetry(String sourceService, int maxAttempts, Duration backoff, Duration maxBackoff, int batchSize);
}
