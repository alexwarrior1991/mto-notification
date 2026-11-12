package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceCursor;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** La marca de agua de cada fuente sondeada y su arrendamiento, decididos por recuento de filas. */
public interface SourceCursorRepository extends JpaRepository<SourceCursor, SourceKind> {

    /** 1 si esta instancia se queda la fuente hasta {@code until}; 0 si otra la tiene. */
    @Modifying
    @Query(value = """
            update source_cursor
               set lease_owner = :owner, lease_until = :until, last_poll_at = now(), updated_at = now()
             where kind = :kind
               and (lease_until is null or lease_until < now())
            """, nativeQuery = true)
    int acquireLease(@Param("kind") String kind, @Param("owner") String owner, @Param("until") Instant until);

    /**
     * A mitad de una pasada larga: renueva el arrendamiento y avanza la marca hasta donde llego
     * (nunca hacia atras). 0 si el arrendamiento ya no es de esta instancia: caduco y otra lo tomo.
     */
    @Modifying
    @Query(value = """
            update source_cursor
               set lease_until = :until,
                   last_event_time = greatest(coalesce(last_event_time, :lastEventTime), coalesce(:lastEventTime, last_event_time)),
                   updated_at = now()
             where kind = :kind and lease_owner = :owner
            """, nativeQuery = true)
    int renewLease(@Param("kind") String kind, @Param("owner") String owner,
                   @Param("lastEventTime") Instant lastEventTime, @Param("until") Instant until);

    /** Suelta el arrendamiento y avanza la marca hasta lo procesado (nunca hacia atras); sin error, la pasada cuenta como buena. */
    @Modifying
    @Query(value = """
            update source_cursor
               set lease_owner = null,
                   lease_until = null,
                   last_event_time = greatest(coalesce(last_event_time, :lastEventTime), coalesce(:lastEventTime, last_event_time)),
                   last_success_at = case when :error is null then now() else last_success_at end,
                   last_error = :error,
                   updated_at = now()
             where kind = :kind and lease_owner = :owner
            """, nativeQuery = true)
    int release(@Param("kind") String kind, @Param("owner") String owner,
                @Param("lastEventTime") Instant lastEventTime, @Param("error") String error);
}
