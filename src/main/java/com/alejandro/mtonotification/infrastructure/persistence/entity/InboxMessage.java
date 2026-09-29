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

/**
 * Cada mensaje que entro por una fuente: por RabbitMQ o por el lector de Keycloak. La garantia de
 * exactamente-una-vez la da la restriccion unica {@code (message_id, source_service)}, y las
 * escrituras son las sentencias nativas de {@code InboxMessageRepository}; el mapeo existe para
 * leer y para que {@code ddl-auto: validate} vigile el esquema.
 */
@Entity
@Table(name = "inbox_message")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class InboxMessage extends AuditableEntity {

    @Column(name = "message_id", nullable = false, updatable = false, length = 200)
    @ToString.Include
    private String messageId;

    @Column(name = "source_service", nullable = false, updatable = false, length = 100)
    @ToString.Include
    private String sourceService;

    @Column(name = "event_type", length = 150)
    @ToString.Include
    private String eventType;

    @Column(name = "aggregate_type", length = 150)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 100)
    private String aggregateId;

    @Column(name = "exchange_name", length = 255)
    private String exchangeName;

    @Column(name = "routing_key", length = 255)
    private String routingKey;

    @Column(name = "queue_name", length = 255)
    private String queueName;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    /** El JSON tal y como llego. {@code json} y no {@code jsonb} para que siga cuadrando con su hash. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "inbox_message_status")
    @ToString.Include
    private InboxMessageStatus status = InboxMessageStatus.RECEIVED;

    @Builder.Default
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Builder.Default
    @Column(name = "processing_attempts", nullable = false)
    @ToString.Include
    private int processingAttempts = 0;

    public boolean isProcessed() {
        return InboxMessageStatus.PROCESSED == status;
    }
}
