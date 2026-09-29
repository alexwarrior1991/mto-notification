package com.alejandro.mtonotification.application.dto.error;

import java.time.Instant;
import java.util.List;

/**
 * Standard immutable HTTP error payload used by every API failure response. The same shape as
 * {@code mto-maintenance} and {@code mto-stock}, which the backoffice already understands.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String method,
        String errorCode,
        String correlationId,
        List<ValidationError> validationErrors
) {

    public ApiErrorResponse {
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
