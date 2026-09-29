package com.alejandro.mtonotification.application.exception;

/**
 * Base unchecked exception for business rule violations raised by the application layer.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
