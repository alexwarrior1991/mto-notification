package com.alejandro.mtonotification.application.dto.common;

import java.util.List;

/**
 * Generic response DTO for paginated API results: {@code {content, page}}, the shape every service
 * of the domain answers with.
 */
public record PageResponse<T>(
        List<T> content,
        PageMetadataResponse page
) {
}
