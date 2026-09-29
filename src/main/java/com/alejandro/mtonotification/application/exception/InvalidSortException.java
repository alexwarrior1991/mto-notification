package com.alejandro.mtonotification.application.exception;

/** 400 {@code REQ-400}: {@code sort} nombra una propiedad por la que este recurso no ordena. */
public class InvalidSortException extends BusinessException {

    private final String property;

    public InvalidSortException(String property) {
        super("Sorting by '" + property + "' is not supported for this resource");
        this.property = property;
    }

    public String getProperty() {
        return property;
    }
}
