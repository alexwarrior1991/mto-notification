package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * El registro. Se escribe con {@link #insertIfMissing}: la idempotencia es la restriccion unica
 * {@code (source_service, source_event_id)}, y un 0 significa que la linea ya estaba.
 */
public interface ActivityEventRepository extends JpaRepository<ActivityEvent, UUID>, JpaSpecificationExecutor<ActivityEvent> {

    Optional<ActivityEvent> findBySourceServiceAndSourceEventId(String sourceService, String sourceEventId);

    @Modifying
    @Query(value = """
            insert into activity_event (
                id, source_service, source_event_id, category, type, severity, occurred_at, recorded_at,
                actor_kind, actor_username, actor_id, subject_type, subject_id, subject_label,
                correlation_id, ip_address, event_count, payload,
                created_at, updated_at, created_by, updated_by
            ) values (
                gen_random_uuid(), :sourceService, :sourceEventId, cast(:category as activity_category), :type,
                cast(:severity as activity_severity), :occurredAt, now(),
                cast(:actorKind as actor_kind), :actorUsername, :actorId, :subjectType, :subjectId, :subjectLabel,
                :correlationId, cast(:ipAddress as inet), :eventCount, cast(:payload as jsonb),
                now(), now(), 'system', 'system'
            ) on conflict (source_service, source_event_id) do nothing
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("sourceService") String sourceService,
            @Param("sourceEventId") String sourceEventId,
            @Param("category") String category,
            @Param("type") String type,
            @Param("severity") String severity,
            @Param("occurredAt") Instant occurredAt,
            @Param("actorKind") String actorKind,
            @Param("actorUsername") String actorUsername,
            @Param("actorId") String actorId,
            @Param("subjectType") String subjectType,
            @Param("subjectId") String subjectId,
            @Param("subjectLabel") String subjectLabel,
            @Param("correlationId") String correlationId,
            @Param("ipAddress") String ipAddress,
            @Param("eventCount") int eventCount,
            @Param("payload") String payload
    );

    /** Fallos de acceso de un usuario en una ventana: lo que cuenta el detector de rachas. */
    @Query("select count(e) from ActivityEvent e where e.type = :type and e.actorUsername = :username "
            + "and e.occurredAt between :from and :to")
    long countByTypeAndActorInWindow(@Param("type") String type, @Param("username") String username,
                                     @Param("from") Instant from, @Param("to") Instant to);

    @Query("select min(e.occurredAt) from ActivityEvent e where e.type = :type and e.actorUsername = :username "
            + "and e.occurredAt between :from and :to")
    Instant earliestByTypeAndActorInWindow(@Param("type") String type, @Param("username") String username,
                                           @Param("from") Instant from, @Param("to") Instant to);

    /** Lo mismo por direccion IP. El parametro es texto y se compara con la columna {@code inet}. */
    @Query(value = "select count(*) from activity_event where type = :type and ip_address = cast(:ip as inet) "
            + "and occurred_at between :from and :to", nativeQuery = true)
    long countByTypeAndIpInWindow(@Param("type") String type, @Param("ip") String ip,
                                  @Param("from") Instant from, @Param("to") Instant to);

    @Query(value = "select min(occurred_at) from activity_event where type = :type and ip_address = cast(:ip as inet) "
            + "and occurred_at between :from and :to", nativeQuery = true)
    Instant earliestByTypeAndIpInWindow(@Param("type") String type, @Param("ip") String ip,
                                        @Param("from") Instant from, @Param("to") Instant to);

    /** Purga por lotes de una categoria: cada una tiene su retencion. */
    @Modifying
    @Query(value = """
            delete from activity_event
             where id in (
                 select id from activity_event
                  where category = cast(:category as activity_category) and recorded_at < :before
                  order by recorded_at
                  limit :batchSize
             )
            """, nativeQuery = true)
    int deleteByCategoryBefore(@Param("category") String category, @Param("before") Instant before,
                               @Param("batchSize") int batchSize);
}
