package com.alejandro.mtonotification.application.dto.admin;

import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterReprocessing;
import com.alejandro.mtonotification.application.dto.deadletter.ReprocessedDeadLetter;

import java.util.List;

/**
 * Una pasada de reproceso: cuantos mensajes se pidieron, cuantos se sacaron y que fue de cada uno.
 * {@code remaining} es lo que guarda la DLQ al terminar: los que volvieron a fallar y los que la
 * pasada no llego a sacar.
 */
public record DeadLetterReprocessResponse(
        String source,
        String deadLetterQueue,
        int requested,
        int fetched,
        long processed,
        long duplicates,
        long failed,
        Long remaining,
        DeadLetterReprocessing.StoppedBy stoppedBy,
        List<ReprocessedDeadLetter> results
) {
}
