package com.alejandro.mtonotification.application.exception;

/** 422: la peticion esta bien formada pero nombra algo que este servicio no puede resolver. */
public class UnprocessableException extends BusinessException {

    private final String aggregate;

    public UnprocessableException(String aggregate, String message) {
        super(message);
        this.aggregate = aggregate;
    }

    public String getAggregate() {
        return aggregate;
    }
}
