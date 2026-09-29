package com.alejandro.mtonotification.infrastructure.persistence.entity;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
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

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * Una linea del registro de actividad. Se escribe con la insercion condicional de
 * {@code ActivityEventRepository} y no se modifica despues, salvo {@code supersededBy}.
 */
@Entity
@Table(name = "activity_event")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class ActivityEvent extends AuditableEntity {

    /** Orden de llegada; lo pone la base. */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    @Column(name = "source_service", nullable = false, updatable = false, length = 100)
    @ToString.Include
    private String sourceService;

    @Column(name = "source_event_id", nullable = false, updatable = false, length = 200)
    @ToString.Include
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "category", nullable = false, columnDefinition = "activity_category")
    @ToString.Include
    private ActivityCategory category;

    @Column(name = "type", nullable = false, length = 150)
    @ToString.Include
    private String type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "severity", nullable = false, columnDefinition = "activity_severity")
    private ActivitySeverity severity;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "actor_kind", nullable = false, columnDefinition = "actor_kind")
    private ActorKind actorKind;

    @Column(name = "actor_username", length = 255)
    private String actorUsername;

    @Column(name = "actor_id", length = 100)
    private String actorId;

    @Column(name = "subject_type", length = 100)
    private String subjectType;

    @Column(name = "subject_id", length = 200)
    private String subjectId;

    @Column(name = "subject_label", length = 255)
    private String subjectLabel;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Solo en ACCESS; lo garantiza el {@code CHECK}. */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address", columnDefinition = "inet")
    private InetAddress ipAddress;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    /** JSON ya pasado por lista blanca. Se guarda como texto: quien lo lee lo interpreta. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "superseded_by")
    private UUID supersededBy;
}
