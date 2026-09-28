package com.alejandro.mtonotification.application.dto.admin;

import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** El estado de las fuentes: las marcas de agua, el inbox por fuente, las rafagas abiertas, las entregas. */
public record SourceStatusResponse(
        List<CursorResponse> cursors,
        List<InboxCountResponse> inbox,
        long openBursts,
        Map<DeliveryStatus, Long> deliveries
) {

    public record CursorResponse(
            SourceKind kind,
            Instant lastEventTime,
            String leaseOwner,
            Instant leaseUntil,
            Instant lastPollAt,
            Instant lastSuccessAt,
            String lastError
    ) {
    }

    public record InboxCountResponse(String sourceService, InboxMessageStatus status, long total) {
    }
}
