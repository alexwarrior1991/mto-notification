package com.alejandro.mtonotification.application.dto.admin;

import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /admin/broadcasts}: un aviso manual. Las audiencias van como {@code KIND:clave}; una
 * clase o un canal desconocidos son un 422.
 */
public record BroadcastRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 4000) String body,
        @Size(max = 500) String link,
        ActivitySeverity severity,
        @NotEmpty List<@NotBlank @Size(max = 300) String> audiences,
        List<@NotBlank @Size(max = 30) String> channels
) {
}
