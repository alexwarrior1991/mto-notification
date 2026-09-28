package com.alejandro.mtonotification.application.mapper;

import com.alejandro.mtonotification.application.dto.common.PageMetadataResponse;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Maps Spring Data pages into API pagination DTOs while preserving mapper-provided content conversions.
 */
public final class PageMapper {

    private PageMapper() {
    }

    public static <S, T> PageResponse<T> toPageResponse(Page<S> page, Function<S, T> contentMapper) {
        List<T> content = page.getContent().stream()
                .map(contentMapper)
                .toList();
        PageMetadataResponse metadata = new PageMetadataResponse(
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
        return new PageResponse<>(content, metadata);
    }
}
