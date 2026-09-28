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

/**
 * A quien va una notificacion. La clave serializada ({@code USER:alice}) es lo que se compara con
 * las del token al leer la bandeja.
 */
@Entity
@Table(name = "notification_audience")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class NotificationAudience {

    @EmbeddedId
    private Id id;

    @Column(name = "kind", nullable = false, length = 30)
    private String kind;

    /** Copia de {@code notification.created_at}, para que el indice por audiencia sirva solo. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Column(name = "notification_id", nullable = false)
        private UUID notificationId;

        @Column(name = "audience_key", nullable = false, length = 300)
        private String audienceKey;
    }
}
