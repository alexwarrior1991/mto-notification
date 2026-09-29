package com.alejandro.mtonotification.application.mapper;

import com.alejandro.mtonotification.application.dto.access.AccessEventResponse;
import com.alejandro.mtonotification.application.dto.access.AccessOutcome;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventResponse;
import com.alejandro.mtonotification.application.dto.activity.ActorResponse;
import com.alejandro.mtonotification.application.dto.activity.SubjectResponse;
import com.alejandro.mtonotification.application.service.impl.JsonPayloads;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetAddress;
import java.util.Map;
import java.util.Set;

/** Entidad → respuesta. El payload va como mapa en el detalle y no viaja en las listas. */
@Mapper(config = MapStructCentralConfig.class)
public abstract class ActivityEventMapper {

    /** Lo que en un acceso cuenta como fallo: lo demas es un acceso que fue bien. */
    public static final Set<String> FAILURE_TYPES = Set.of(
            ActivityTypes.ACCESS_LOGIN_FAILED, ActivityTypes.ACCESS_LOGIN_STREAK,
            ActivityTypes.ACCESS_LOCKOUT, ActivityTypes.ACCESS_LOGOUT_FAILED);

    @Autowired
    protected JsonPayloads jsonPayloads;

    @Mapping(target = "actor", expression = "java(actor(event))")
    @Mapping(target = "subject", expression = "java(subject(event))")
    @Mapping(target = "payload", expression = "java(jsonPayloads.read(event.getPayload()))")
    public abstract ActivityEventResponse toDetail(ActivityEvent event);

    @Mapping(target = "actor", expression = "java(actor(event))")
    @Mapping(target = "subject", expression = "java(subject(event))")
    @Mapping(target = "payload", ignore = true)
    public abstract ActivityEventResponse toSummary(ActivityEvent event);

    @Mapping(target = "outcome", expression = "java(outcome(event.getType()))")
    @Mapping(target = "username", source = "actorUsername")
    @Mapping(target = "userId", source = "actorId")
    @Mapping(target = "ipAddress", expression = "java(ip(event.getIpAddress()))")
    @Mapping(target = "payload", expression = "java(jsonPayloads.read(event.getPayload()))")
    public abstract AccessEventResponse toAccess(ActivityEvent event);

    protected static ActorResponse actor(ActivityEvent event) {
        return new ActorResponse(event.getActorKind(), event.getActorUsername(), event.getActorId());
    }

    protected static SubjectResponse subject(ActivityEvent event) {
        return new SubjectResponse(event.getSubjectType(), event.getSubjectId(), event.getSubjectLabel());
    }

    protected static String ip(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }

    public static AccessOutcome outcome(String type) {
        return FAILURE_TYPES.contains(type) ? AccessOutcome.FAILURE : AccessOutcome.SUCCESS;
    }

    protected Map<String, Object> payload(String json) {
        return jsonPayloads.read(json);
    }
}
