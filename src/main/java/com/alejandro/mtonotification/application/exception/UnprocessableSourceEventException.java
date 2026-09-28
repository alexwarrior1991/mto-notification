package com.alejandro.mtonotification.application.exception;

/**
 * Un evento de una fuente que no se puede interpretar y no va a mejorar por reintentarlo: sin
 * entidad, sin operacion, con una forma que no es la del contrato. El consumidor lo manda a la
 * DLQ sin gastar intentos; el inbox lo deja FAILED con el motivo.
 */
public class UnprocessableSourceEventException extends BusinessException {

    public UnprocessableSourceEventException(String message) {
        super(message);
    }
}
