package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.deadletter.DeadLetter;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterQueueDepth;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterReprocessing;

import java.time.Duration;
import java.util.List;

/**
 * Las DLQ de las fuentes, vistas desde la aplicacion: cuantos mensajes guardan, cuales son y volver
 * a pasarlos por el camino de siempre. Solo existe con el broker encendido
 * ({@code app.rabbitmq.enabled}); nada de aqui corre dentro de una transaccion de base de datos.
 *
 * <p>Un {@code source} que no esta configurado es {@code NotFoundException}; una DLQ que no existe
 * en el broker, {@code ConflictException}; un broker que no responde,
 * {@code BrokerUnavailableException}.</p>
 */
public interface DeadLetterQueues {

    /** Una linea por fuente configurada, por orden alfabetico. */
    List<DeadLetterQueueDepth> depths();

    /** La DLQ de una fuente. */
    DeadLetterQueueDepth depth(String source);

    /** Los primeros {@code max} mensajes de la DLQ, que se quedan en ella. */
    List<DeadLetter> peek(String source, int max);

    /**
     * Pasa hasta {@code max} mensajes de la DLQ por el consumidor de su fuente, uno a uno y cada uno
     * una sola vez: el que se aplica (o ya estaba aplicado) sale de la DLQ, y el que vuelve a fallar
     * se queda en ella. Deja de sacar mensajes pasado {@code timeBudget}.
     */
    DeadLetterReprocessing reprocess(String source, int max, Duration timeBudget);
}
