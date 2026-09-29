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

    /** Suelta la fuente; con {@code error} nulo avanza la marca a {@code lastEventTime} (nunca hacia atras). */
    void release(Lease lease, Instant lastEventTime, String error);

    List<SourceCursor> all();

    record Lease(SourceKind kind, String owner, Instant lastEventTime, Instant lastSuccessAt) {
    }
}
