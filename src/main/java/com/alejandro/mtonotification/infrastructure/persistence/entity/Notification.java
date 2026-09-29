package com.alejandro.mtonotification.infrastructure.persistence.entity;

import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
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

import java.util.UUID;

/**
 * Un aviso: lo que una regla (o una persona, en un aviso manual) decidio que merecia contarse. A
 * quien va esta en {@code notification_audience}; si alguien lo leyo, en {@code notification_receipt}.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Notification extends AuditableEntity {

    @Column(name = "rule_key", nullable = false, updatable = false, length = 100)
    @ToString.Include
    private String ruleKey;

    @Column(name = "activity_event_id")
    private UUID activityEventId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "category", nullable = false, columnDefinition = "activity_category")
    private ActivityCategory category;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "severity", nullable = false, columnDefinition = "activity_severity")
    @ToString.Include
    private ActivitySeverity severity;

    @Column(name = "title", nullable = false, length = 255)
    @ToString.Include
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "link", length = 500)
    private String link;

    @Column(name = "subject_type", length = 100)
    private String subjectType;

    @Column(name = "subject_id", length = 200)
    private String subjectId;
}
