package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationAudience;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationAudienceRepository extends JpaRepository<NotificationAudience, NotificationAudience.Id> {

    List<NotificationAudience> findByIdNotificationIdIn(Collection<UUID> notificationIds);
}
