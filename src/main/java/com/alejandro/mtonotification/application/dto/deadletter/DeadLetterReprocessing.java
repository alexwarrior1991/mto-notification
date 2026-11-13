package com.alejandro.mtonotification.application.dto.deadletter;

import java.util.List;

/** El resultado de una pasada de reproceso sobre la DLQ de una fuente, mensaje a mensaje y en su orden. */
public record DeadLetterReprocessing(List<ReprocessedDeadLetter> results, StoppedBy stoppedBy) {

    public DeadLetterReprocessing {
        results = List.copyOf(results);
    }

    public long count(ReprocessedDeadLetter.Outcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }

    /** Por que termino la pasada. */
    public enum StoppedBy {

        /** La DLQ no tenia mas mensajes que esta pasada no hubiera visto. */
        EMPTY,

        /** Se alcanzo el maximo pedido. */
        MAX,

        /** Se agoto el tiempo de la pasada: lo que quedaba ni se saco de la cola. */
        TIME_BUDGET
    }
}
