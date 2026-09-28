package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** Las notificaciones. La bandeja de una persona es una {@code Specification} sobre sus claves de audiencia. */
public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    /** La mas reciente visible con estas claves: hasta ahi llega «marcar todas como leidas». */
    @Query("select max(n.createdAt) from Notification n where exists ("
            + "select a from NotificationAudience a where a.id.notificationId = n.id and a.id.audienceKey in :keys)")
    Instant findLatestCreatedAtVisibleTo(@Param("keys") Collection<String> keys);

    @Query("select case when count(a) > 0 then true else false end from NotificationAudience a "
            + "where a.id.notificationId = :notificationId and a.id.audienceKey in :keys")
    boolean isVisibleTo(@Param("notificationId") UUID notificationId, @Param("keys") Collection<String> keys);

    /** Purga por lotes; audiencias, recibos y entregas se van con cada notificacion (cascade). */
    @Modifying
    @Query(value = """
            delete from notification
             where id in (
                 select id from notification
                  where created_at < :before
                  order by created_at
                  limit :batchSize
             )
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
