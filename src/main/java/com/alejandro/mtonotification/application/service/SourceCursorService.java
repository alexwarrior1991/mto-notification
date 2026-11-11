package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceCursor;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** La marca de agua y el arrendamiento de cada fuente sondeada. */
public interface SourceCursorService {

    /** @return el arrendamiento con la marca actual, o vacio si otra instancia tiene la fuente. */
    Optional<Lease> acquire(SourceKind kind);

    /**
     * A mitad de una pasada: renueva el arrendamiento y avanza la marca a {@code lastEventTime} (nunca
     * hacia atras; {@code null} la deja).
     *
     * @return {@code false} si el arrendamiento ya no es de esta instancia: la pasada tiene que parar
     */
    boolean advance(Lease lease, Instant lastEventTime);

    /**
     * Suelta la fuente y avanza la marca a {@code lastEventTime} (nunca hacia atras; {@code null} la
     * deja): es el prefijo ya procesado, tambien cuando la pasada acaba con {@code error}.
     */
    void release(Lease lease, Instant lastEventTime, String error);

    List<SourceCursor> all();

    record Lease(SourceKind kind, String owner, Instant lastEventTime, Instant lastSuccessAt) {
    }
}
