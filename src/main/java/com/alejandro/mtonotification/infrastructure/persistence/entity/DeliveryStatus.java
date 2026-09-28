package com.alejandro.mtonotification.infrastructure.persistence.entity;

/** Estado de una entrega. Coincide con {@code delivery_status}. */
public enum DeliveryStatus {

    /** A la espera de que el despachador la reclame (o de su proximo intento). */
    PENDING,

    /** Reclamada por una instancia; si esta muere, vuelve a estar disponible pasado el plazo. */
    IN_PROGRESS,

    /** Enviada (una RECIPIENT) o expandida (una AUDIENCE). */
    SENT,

    /** Agotados los intentos. Se puede reintentar desde la administracion. */
    FAILED,

    /** No se envia y no se reintenta sola: sin direccion, canal apagado, tope por hora. */
    SKIPPED
}
