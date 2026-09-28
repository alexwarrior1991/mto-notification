package com.alejandro.mtonotification.infrastructure.persistence.entity;

/**
 * Ciclo de vida de un mensaje del inbox. Coincide con el tipo {@code inbox_message_status}.
 */
public enum InboxMessageStatus {

    /** Registrado y sin reclamar; en el flujo normal dura lo que tarda la misma transaccion. */
    RECEIVED,

    /** Reclamado por una entrega en curso. Encontrarlo confirmado significa que el proceso murio a mitad. */
    PROCESSING,

    /** Aplicado. Una entrega posterior del mismo mensaje se descarta sin ejecutar nada. */
    PROCESSED,

    /** El manejador fallo; la fila conserva el motivo y una reentrega vuelve a reclamarla. */
    FAILED
}
