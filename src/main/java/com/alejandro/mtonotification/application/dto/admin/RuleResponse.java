package com.alejandro.mtonotification.application.dto.admin;

import com.alejandro.mtonotification.domain.model.ActivitySeverity;

import java.time.Duration;
import java.util.List;

/** Una regla cargada, tal como esta en el YAML. */
public record RuleResponse(
        String key,
        List<String> events,
        String when,
        ActivitySeverity severity,
        List<String> audiences,
        List<String> channels,
        String title,
        String body,
        String link,
        Duration throttleWindow,
        String throttleKey
) {
}
