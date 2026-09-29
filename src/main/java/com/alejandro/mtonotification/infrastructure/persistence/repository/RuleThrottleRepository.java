package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.RuleThrottle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RuleThrottleRepository extends JpaRepository<RuleThrottle, RuleThrottle.Id> {

    /**
     * Un disparo por regla, clave y ventana, decidido en la sentencia: 1 si esta llamada dispara
     * (no habia freno, o el que habia ya vencio), 0 si el freno sigue puesto. Dos instancias con el
     * mismo evento se serializan en la clave primaria y solo una recibe el 1.
     */
    @Modifying
    @Query(value = """
            insert into rule_throttle (rule_key, dimension_key, fired_at, until)
            values (:ruleKey, :dimensionKey, now(), now() + make_interval(secs => :windowSeconds))
            on conflict (rule_key, dimension_key) do update
               set fired_at = now(), until = excluded.until
             where rule_throttle.until <= now()
            """, nativeQuery = true)
    int tryAcquire(@Param("ruleKey") String ruleKey, @Param("dimensionKey") String dimensionKey,
                   @Param("windowSeconds") long windowSeconds);

    @Modifying
    @Query(value = """
            delete from rule_throttle
             where (rule_key, dimension_key) in (
                 select rule_key, dimension_key from rule_throttle
                  where until < :before
                  order by until
                  limit :batchSize
             )
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
