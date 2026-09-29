package com.alejandro.mtonotification.application.dto.error;

/**
 * Describes one field, parameter or object validation failure returned to API clients.
 */
public record ValidationError(
        String field,
        String message
) {
}
