package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessage;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * El inbox y sus operaciones atomicas de idempotencia, copia de las de mto-maintenance: las
 * escrituras son nativas y condicionales, y decide la base de datos por recuento de filas. Una
 * primera entrega y su duplicado simultaneo se serializan en el indice unico; entregas posteriores,
 * en el bloqueo de fila de {@link #claimForProcessing}. Por los dos caminos el trabajo corre una vez.
 */
public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {

    Optional<InboxMessage> findByMessageIdAndSourceService(String messageId, String sourceService);

    List<InboxMessage> findByStatus(InboxMessageStatus status);

    long countBySourceServiceAndStatus(String sourceService, InboxMessageStatus status);

    /** Recuento por fuente y estado, para la administracion. */
    @Query("select m.sourceService as sourceService, m.status as status, count(m) as total "
            + "from InboxMessage m group by m.sourceService, m.status order by m.sourceService, m.status")
    List<SourceStatusCount> countBySourceAndStatus();

    interface SourceStatusCount {
        String getSourceService();

        InboxMessageStatus getStatus();

        long getTotal();
    }

    /** Registra el mensaje si es la primera vez que se ve; 0 si ya estaba, que no significa aplicado. */
    @Modifying
    @Query(value = """
            insert into inbox_message (
                id, message_id, source_service, event_type, aggregate_type, aggregate_id,
                exchange_name, routing_key, queue_name, payload_hash, payload, status, received_at,
                processing_attempts, created_at, updated_at, created_by, updated_by
            ) values (
                gen_random_uuid(), :messageId, :sourceService, :eventType, :aggregateType, :aggregateId,
                :exchangeName, :routingKey, :queueName, :payloadHash, cast(:payload as json), 'RECEIVED', now(),
                0, now(), now(), 'system', 'system'
            ) on conflict (message_id, source_service) do nothing
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("messageId") String messageId,
            @Param("sourceService") String sourceService,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("exchangeName") String exchangeName,
            @Param("routingKey") String routingKey,
            @Param("queueName") String queueName,
            @Param("payloadHash") String payloadHash,
            @Param("payload") String payload
    );

    /**
     * Reclama el mensaje salvo que ya este aplicado: 1 si esta entrega se queda el trabajo, 0 si es
     * un duplicado. Un PROCESSING se reclama (el proceso que lo escribio murio a mitad) y el motivo
     * del fallo anterior se limpia.
     */
    @Modifying
    @Query(value = """
            update inbox_message
               set status = 'PROCESSING',
                   processing_attempts = processing_attempts + 1,
                   failed_at = null,
                   failure_reason = null,
                   updated_at = now()
             where message_id = :messageId
               and source_service = :sourceService
               and status <> 'PROCESSED'
            """, nativeQuery = true)
    int claimForProcessing(@Param("messageId") String messageId, @Param("sourceService") String sourceService);

    @Modifying
    @Query(value = """
            update inbox_message
               set status = 'PROCESSED',
                   processed_at = now(),
                   updated_at = now()
             where message_id = :messageId
               and source_service = :sourceService
               and status = 'PROCESSING'
            """, nativeQuery = true)
    int markProcessed(@Param("messageId") String messageId, @Param("sourceService") String sourceService);

    /**
     * Deja constancia del fallo, exista ya la fila o no: se ejecuta en una transaccion aparte,
     * despues de que la del intento haya revertido y se haya llevado la fila recien insertada.
     */
    @Modifying
    @Query(value = """
            insert into inbox_message (
                id, message_id, source_service, event_type, aggregate_type, aggregate_id,
                exchange_name, routing_key, queue_name, payload_hash, payload, status, received_at,
                failed_at, failure_reason, processing_attempts, created_at, updated_at, created_by, updated_by
            ) values (
                gen_random_uuid(), :messageId, :sourceService, :eventType, :aggregateType, :aggregateId,
                :exchangeName, :routingKey, :queueName, :payloadHash, cast(:payload as json), 'FAILED', now(),
                now(), :failureReason, 1, now(), now(), 'system', 'system'
            ) on conflict (message_id, source_service) do update
               set status = 'FAILED',
                   failed_at = now(),
                   failure_reason = excluded.failure_reason,
                   processing_attempts = inbox_message.processing_attempts + 1,
                   updated_at = now()
            """, nativeQuery = true)
    int recordFailure(
            @Param("messageId") String messageId,
            @Param("sourceService") String sourceService,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("exchangeName") String exchangeName,
            @Param("routingKey") String routingKey,
            @Param("queueName") String queueName,
            @Param("payloadHash") String payloadHash,
            @Param("payload") String payload,
            @Param("failureReason") String failureReason
    );

    /** Purga por lotes de lo ya aplicado: el JSON crudo de una fuente no vive mas de lo configurado. */
    @Modifying
    @Query(value = """
            delete from inbox_message
             where id in (
                 select id from inbox_message
                  where status = 'PROCESSED' and received_at < :before
                  order by received_at
                  limit :batchSize
             )
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
