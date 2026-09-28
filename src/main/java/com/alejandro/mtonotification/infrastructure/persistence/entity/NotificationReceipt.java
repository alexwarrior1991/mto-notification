package com.alejandro.mtonotification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Una persona leyo una notificacion. Gana a {@code inbox_state.all_read_until}. */
@Entity
@Table(name = "notification_receipt")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class NotificationReceipt {

    @EmbeddedId
    private Id id;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Column(name = "notification_id", nullable = false)
        private UUID notificationId;

        @Column(name = "username", nullable = false, length = 255)
        private String username;
    }
}
