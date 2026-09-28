package com.alejandro.mtonotification.application.dto.common;

/**
 * Response DTO describing pagination metadata for collection resources.
 */
public record PageMetadataResponse(
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
