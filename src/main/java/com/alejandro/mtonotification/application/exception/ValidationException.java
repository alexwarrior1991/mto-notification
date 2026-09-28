package com.alejandro.mtonotification.application.exception;

/**
 * Raised when a business command contains invalid state beyond DTO-level validation.
 */
public class ValidationException extends BusinessException {

    public ValidationException(String message) {
        super(message);
    }
}
