package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationReceiptRepository extends JpaRepository<NotificationReceipt, NotificationReceipt.Id> {

    List<NotificationReceipt> findByIdUsernameAndIdNotificationIdIn(String username, Collection<UUID> notificationIds);

    /** Marcar leida es idempotente: la segunda vez no cambia la fecha de la primera. */
    @Modifying
    @Query(value = """
            insert into notification_receipt (notification_id, username, read_at)
            values (:notificationId, :username, now())
            on conflict (notification_id, username) do nothing
            """, nativeQuery = true)
    int insertIfMissing(@Param("notificationId") UUID notificationId, @Param("username") String username);
}
