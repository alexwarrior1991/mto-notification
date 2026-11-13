package com.alejandro.mtonotification.application.dto.deadletter;

/** Que paso con un mensaje de la DLQ al volver a procesarlo. */
public record ReprocessedDeadLetter(String messageId, String eventType, String routingKey, Outcome outcome, String reason) {

    public enum Outcome {

        /** Aplicado ahora: ya no esta en la DLQ. */
        PROCESSED,

        /** El inbox ya lo tenia aplicado: no se ejecuto nada y ya no esta en la DLQ. */
        DUPLICATE,

        /** Volvio a fallar: sigue en la DLQ, con el motivo. */
        FAILED
    }
}
