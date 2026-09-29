package com.alejandro.mtonotification.application.mapper;

import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.notification.NotificationResponse;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.List;

/** Entidad → respuesta: la de la bandeja lleva leida o no leida; la de administracion, sus audiencias. */
@Mapper(config = MapStructCentralConfig.class)
public interface NotificationMapper {

    @Mapping(target = "id", source = "notification.id")
    @Mapping(target = "createdAt", source = "notification.createdAt")
    @Mapping(target = "read", source = "read")
    @Mapping(target = "readAt", source = "readAt")
    InboxItemResponse toInboxItem(Notification notification, boolean read, Instant readAt);

    @Mapping(target = "id", source = "notification.id")
    @Mapping(target = "createdAt", source = "notification.createdAt")
    @Mapping(target = "createdBy", source = "notification.createdBy")
    @Mapping(target = "audiences", source = "audiences")
    NotificationResponse toResponse(Notification notification, List<String> audiences);
}
