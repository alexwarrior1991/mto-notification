package com.alejandro.mtonotification.application.dto.inbox;

/** El contador de la campana, acotado: con {@code capped} la cifra es «99+», no un total. */
public record UnreadCountResponse(long count, boolean capped) {
}
