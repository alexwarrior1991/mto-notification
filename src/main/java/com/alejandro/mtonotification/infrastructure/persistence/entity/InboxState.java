package com.alejandro.mtonotification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** «Marcar todas como leidas», por persona: todo lo creado hasta {@code allReadUntil} esta leido. */
@Entity
@Table(name = "inbox_state")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InboxState {

    @Id
    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "all_read_until")
    private Instant allReadUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
