package com.alejandro.mtonotification.application.dto.messaging;

/** Que hizo el inbox con un mensaje. Un fallo es una excepcion, no un valor: nadie puede olvidarse de mirarlo. */
public enum InboxProcessingResult {

    /** Primera aplicacion efectiva. */
    PROCESSED,

    /** Entrega repetida de un mensaje ya aplicado: nada se ejecuto y se confirma igualmente. */
    DUPLICATE_SKIPPED
}
