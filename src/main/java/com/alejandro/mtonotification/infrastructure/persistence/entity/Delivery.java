package com.alejandro.mtonotification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Una entrega por un canal que empuja. Ver {@link DeliveryScope}. */
@Entity
@Table(name = "delivery")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Delivery extends AuditableEntity {

    @Column(name = "notification_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID notificationId;

    @Column(name = "channel", nullable = false, updatable = false, length = 30)
    @ToString.Include
    private String channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "scope", nullable = false, updatable = false, columnDefinition = "delivery_scope")
    @ToString.Include
    private DeliveryScope scope;

    @Column(name = "audience_kind", length = 30)
    private String audienceKind;

    @Column(name = "audience_key", length = 300)
    private String audienceKey;

    /** La direccion del canal: el correo. */
    @Column(name = "recipient", length = 320)
    private String recipient;

    @Column(name = "recipient_username", length = 255)
    private String recipientUsername;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "delivery_status")
    @ToString.Include
    private DeliveryStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
