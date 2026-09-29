package com.alejandro.mtonotification.infrastructure.persistence.entity;

import com.alejandro.mtonotification.domain.model.ActorKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Una rafaga de eventos de datos maestros de la misma clave. La escribe el upsert de
 * {@code ActivityBurstRepository}; la cierra el planificador y la convierte en una linea.
 */
@Entity
@Table(name = "activity_burst")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class ActivityBurst extends AuditableEntity {

    @Column(name = "burst_key", nullable = false, length = 500)
    @ToString.Include
    private String burstKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "activity_burst_status")
    @ToString.Include
    private ActivityBurstStatus status;

    @Column(name = "source_service", nullable = false, length = 100)
    private String sourceService;

    @Column(name = "entity_name", nullable = false, length = 150)
    private String entityName;

    @Column(name = "operation", nullable = false, length = 50)
    private String operation;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "actor_kind", nullable = false, columnDefinition = "actor_kind")
    private ActorKind actorKind;

    @Column(name = "actor_username", length = 255)
    private String actorUsername;

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "event_count", nullable = false)
    @ToString.Include
    private int eventCount;

    /** Hasta N identificadores, como JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sample_ids", nullable = false, columnDefinition = "jsonb")
    private String sampleIds;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "last_event_at", nullable = false)
    private Instant lastEventAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
