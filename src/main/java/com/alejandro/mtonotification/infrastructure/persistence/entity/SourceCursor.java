package com.alejandro.mtonotification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * La marca de agua de una fuente sondeada y su arrendamiento. Las filas las crea la migracion; el
 * lector solo las actualiza con las sentencias de {@code SourceCursorRepository}.
 */
@Entity
@Table(name = "source_cursor")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceCursor {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40)
    private SourceKind kind;

    /** El {@code time} del evento mas reciente ya procesado; {@code null} antes de la primera pasada. */
    @Column(name = "last_event_time")
    private Instant lastEventTime;

    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_poll_at")
    private Instant lastPollAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
