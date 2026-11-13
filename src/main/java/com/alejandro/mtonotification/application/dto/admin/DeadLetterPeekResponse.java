package com.alejandro.mtonotification.application.dto.admin;

import com.alejandro.mtonotification.application.dto.deadletter.DeadLetter;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessageStatus;

import java.time.Instant;
import java.util.List;

/**
 * Los primeros mensajes de la DLQ de una fuente, que siguen en ella. {@code messages} es lo que
 * guardaba la cola antes de mirar.
 */
public record DeadLetterPeekResponse(String source, String deadLetterQueue, long messages, List<PeekedDeadLetter> peeked) {

    /**
     * Un mensaje de la DLQ y lo que el inbox sabe de el: {@code inbox} es {@code null} si nunca
     * llego a registrarse (lo paro la firma, o no traia identificador) o si su fila ya se purgo.
     */
    public record PeekedDeadLetter(DeadLetter message, InboxRecord inbox) {
    }

    public record InboxRecord(InboxMessageStatus status, int attempts, Instant failedAt, String failureReason) {
    }
}
