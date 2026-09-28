package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Las entregas. El despachador reclama con {@code for update skip locked} y decide cada fila por
 * recuento; las inserciones son {@code on conflict do nothing} sobre los indices unicos parciales,
 * que son la idempotencia por notificacion, destinatario y canal.
 */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {

    long countByStatus(DeliveryStatus status);

    List<Delivery> findByNotificationIdOrderByCreatedAtAsc(UUID notificationId);

    @Query("select d.status as status, count(d) as total from Delivery d group by d.status")
    List<StatusCount> countGroupedByStatus();

    interface StatusCount {
        DeliveryStatus getStatus();

        long getTotal();
    }

    /** Escrita por la ingesta, una por (notificacion, canal, audiencia). */
    @Modifying
    @Query(value = """
            insert into delivery (
                id, notification_id, channel, scope, audience_kind, audience_key, status, attempts, max_attempts,
                next_attempt_at, created_at, updated_at, created_by, updated_by
            ) values (
                gen_random_uuid(), :notificationId, :channel, 'AUDIENCE', :audienceKind, :audienceKey, 'PENDING', 0,
                :maxAttempts, now(), now(), now(), 'system', 'system'
            ) on conflict (notification_id, channel, audience_key) where scope = 'AUDIENCE' do nothing
            """, nativeQuery = true)
    int insertAudienceIfMissing(@Param("notificationId") UUID notificationId, @Param("channel") String channel,
                                @Param("audienceKind") String audienceKind, @Param("audienceKey") String audienceKey,
                                @Param("maxAttempts") int maxAttempts);

    /** Escrita por el despachador al expandir una audiencia, o por la administracion (correo de prueba). */
    @Modifying
    @Query(value = """
            insert into delivery (
                id, notification_id, channel, scope, recipient, recipient_username, status, attempts, max_attempts,
                next_attempt_at, last_error, created_at, updated_at, created_by, updated_by
            ) values (
                gen_random_uuid(), :notificationId, :channel, 'RECIPIENT', :recipient, :recipientUsername,
                cast(:status as delivery_status), 0, :maxAttempts, now(), :reason, now(), now(), 'system', 'system'
            ) on conflict (notification_id, channel, recipient) where scope = 'RECIPIENT' do nothing
            """, nativeQuery = true)
    int insertRecipientIfMissing(@Param("notificationId") UUID notificationId, @Param("channel") String channel,
                                 @Param("recipient") String recipient, @Param("recipientUsername") String recipientUsername,
                                 @Param("status") String status, @Param("maxAttempts") int maxAttempts,
                                 @Param("reason") String reason);

    /**
     * Lo que toca despachar, bloqueado para esta instancia. Una IN_PROGRESS cuyo plazo de
     * visibilidad paso vuelve a salir: la instancia que la reclamo murio con ella.
     */
    @Query(value = """
            select id from delivery
             where status in ('PENDING', 'IN_PROGRESS')
               and next_attempt_at <= :now
             order by next_attempt_at
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<UUID> findDueIdsForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    /** Reclama las filas encontradas: cuenta el intento y las oculta hasta que venza la visibilidad. */
    @Modifying
    @Query(value = """
            update delivery
               set status = 'IN_PROGRESS', attempts = attempts + 1, claimed_at = now(),
                   next_attempt_at = :visibleAgainAt, updated_at = now()
             where id in :ids
            """, nativeQuery = true)
    int claim(@Param("ids") Collection<UUID> ids, @Param("visibleAgainAt") Instant visibleAgainAt);

    @Modifying
    @Query(value = """
            update delivery
               set status = 'SENT', sent_at = now(), last_error = null, updated_at = now()
             where id = :id and status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int markSent(@Param("id") UUID id);

    /** Un fallo con intentos por delante: vuelve a PENDING para cuando toque. */
    @Modifying
    @Query(value = """
            update delivery
               set status = 'PENDING', next_attempt_at = :nextAttemptAt, last_error = :error, updated_at = now()
             where id = :id and status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int reschedule(@Param("id") UUID id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error);

    @Modifying
    @Query(value = """
            update delivery
               set status = cast(:status as delivery_status), last_error = :reason, updated_at = now()
             where id = :id and status = 'IN_PROGRESS'
            """, nativeQuery = true)
    int settle(@Param("id") UUID id, @Param("status") String status, @Param("reason") String reason);

    /** Desde la administracion: una FAILED o SKIPPED vuelve a la cola con los intentos a cero. */
    @Modifying
    @Query(value = """
            update delivery
               set status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = null, updated_at = now()
             where id = :id and status in ('FAILED', 'SKIPPED')
            """, nativeQuery = true)
    int retry(@Param("id") UUID id);

    /** Correos enviados a una direccion desde un instante: el tope por hora. */
    @Query(value = "select count(*) from delivery where status = 'SENT' and channel = :channel "
            + "and recipient = :recipient and sent_at >= :since", nativeQuery = true)
    long countSentToRecipientSince(@Param("channel") String channel, @Param("recipient") String recipient,
                                   @Param("since") Instant since);
}
