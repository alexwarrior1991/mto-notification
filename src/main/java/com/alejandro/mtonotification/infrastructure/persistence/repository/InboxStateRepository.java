package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InboxStateRepository extends JpaRepository<InboxState, String> {

    /** Solo avanza: una marca anterior a la guardada no la retrasa. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into inbox_state (username, all_read_until, updated_at)
            values (:username, :until, now())
            on conflict (username) do update
               set all_read_until = greatest(coalesce(inbox_state.all_read_until, excluded.all_read_until), excluded.all_read_until),
                   updated_at = now()
            """, nativeQuery = true)
    int markAllReadUntil(@Param("username") String username, @Param("until") Instant until);
}
