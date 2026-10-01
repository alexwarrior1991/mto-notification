package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityBurst;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityBurstStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Las rafagas. El upsert de {@link #recordEvent} es el destino de cada evento agregado y decide
 * en el indice unico parcial: solo hay una rafaga abierta por clave, y un consumidor que espere
 * en la fila que el cerrador tiene bloqueada la encuentra cerrada al soltarse y abre otra.
 */
public interface ActivityBurstRepository extends JpaRepository<ActivityBurst, UUID> {

    long countByStatus(ActivityBurstStatus status);

    @Modifying
    @Query(value = """
            insert into activity_burst (
                id, burst_key, status, source_service, entity_name, operation, actor_kind, actor_username, actor_id,
                correlation_id, event_count, sample_ids, opened_at, last_event_at,
                created_at, updated_at, created_by, updated_by
            ) values (
                gen_random_uuid(), :burstKey, 'OPEN', :sourceService, :entityName, :operation,
                cast(:actorKind as actor_kind), :actorUsername, :actorId, :correlationId, 1,
                case when :sampleId is null then '[]'::jsonb else jsonb_build_array(cast(:sampleId as text)) end,
                :eventAt, :eventAt, now(), now(), 'system', 'system'
            ) on conflict (burst_key) where status = 'OPEN' do update
               set event_count = activity_burst.event_count + 1,
                   last_event_at = greatest(activity_burst.last_event_at, excluded.last_event_at),
                   sample_ids = case
                       when :sampleId is not null and jsonb_array_length(activity_burst.sample_ids) < :sampleSize
                           then activity_burst.sample_ids || jsonb_build_array(cast(:sampleId as text))
                       else activity_burst.sample_ids
                   end,
                   updated_at = now()
            """, nativeQuery = true)
    int recordEvent(
            @Param("burstKey") String burstKey,
            @Param("sourceService") String sourceService,
            @Param("entityName") String entityName,
            @Param("operation") String operation,
            @Param("actorKind") String actorKind,
            @Param("actorUsername") String actorUsername,
            @Param("actorId") String actorId,
            @Param("correlationId") String correlationId,
            @Param("sampleId") String sampleId,
            @Param("sampleSize") int sampleSize,
            @Param("eventAt") Instant eventAt
    );

    /**
     * Las abiertas que llevan un rato ociosas o demasiado tiempo abiertas, bloqueadas para este
     * cerrador: otra instancia que cierre a la vez se salta las que ya tiene alguien. «Demasiado» depende
     * de si la rafaga es de un trabajo: con {@code correlation_id} (los eventos de una importacion llevan
     * su jobId) el tope es {@code correlatedOpenedBefore}, para que una importacion larga sea una linea;
     * sin el, {@code openedBefore}.
     */
    @Query(value = """
            select * from activity_burst
             where status = 'OPEN'
               and (last_event_at < :idleBefore
                    or (correlation_id is null and opened_at < :openedBefore)
                    or (correlation_id is not null and opened_at < :correlatedOpenedBefore))
             order by last_event_at
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<ActivityBurst> findExpiredOpenForUpdate(@Param("idleBefore") Instant idleBefore,
                                                 @Param("openedBefore") Instant openedBefore,
                                                 @Param("correlatedOpenedBefore") Instant correlatedOpenedBefore,
                                                 @Param("limit") int limit);

    @Modifying
    @Query(value = """
            update activity_burst
               set status = 'CLOSED', closed_at = now(), updated_at = now()
             where id = :id and status = 'OPEN'
            """, nativeQuery = true)
    int close(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            delete from activity_burst
             where id in (
                 select id from activity_burst
                  where status = 'CLOSED' and closed_at < :before
                  order by closed_at
                  limit :batchSize
             )
            """, nativeQuery = true)
    int deleteClosedBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
