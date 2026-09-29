package com.alejandro.mtonotification.application.dto.inbox;

import java.time.Instant;

/** Hasta donde quedo marcado todo como leido; {@code null} si no habia nada que marcar. */
public record ReadAllResponse(Instant allReadUntil) {
}
