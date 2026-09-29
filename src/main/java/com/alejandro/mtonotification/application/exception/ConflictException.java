package com.alejandro.mtonotification.application.exception;

/** 409: el recurso existe pero su estado no admite la operacion. El codigo sale del agregado. */
public class ConflictException extends BusinessException {

    private final String aggregate;

    public ConflictException(String aggregate, String message) {
        super(message);
        this.aggregate = aggregate;
    }

    public String getAggregate() {
        return aggregate;
    }
}
