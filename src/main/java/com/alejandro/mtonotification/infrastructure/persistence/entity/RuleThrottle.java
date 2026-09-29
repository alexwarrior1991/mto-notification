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

/** Un disparo de una regla sobre una clave, y hasta cuando frena. Lo escribe el upsert condicional. */
@Entity
@Table(name = "rule_throttle")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RuleThrottle {

    @EmbeddedId
    private Id id;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @Column(name = "until", nullable = false)
    private Instant until;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Column(name = "rule_key", nullable = false, length = 100)
        private String ruleKey;

        @Column(name = "dimension_key", nullable = false, length = 300)
        private String dimensionKey;
    }
}
