package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.ActivitySourceAdapter;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.Subject;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Las acciones administrativas de mto-users ({@code mto.users.<entidad>.<evento>}), una linea por
 * accion con la persona que la hizo: {@code users.user.created}, {@code users.profile.assigned},
 * {@code users.session.all-revoked}... El sujeto es siempre el usuario objetivo, que es lo que
 * permite fundir cada linea con el evento de administracion de Keycloak del mismo cambio
 * ({@link UsersChangeCorrelator}). El nombre del usuario objetivo viaja cuando mto-users lo sabe;
 * cuando no (roles, perfiles, sesiones), la regla avisa a la persona por su id ({@code USER_ID}).
 */
@Service
@RequiredArgsConstructor
class UsersSourceAdapter implements ActivitySourceAdapter {

    static final String SOURCE_ID = "users";
    static final String DEFAULT_ORIGIN = "mto-users";
    static final String SUBJECT_TYPE = "user";

    /** {@code users.admin.*} es de los eventos de administracion de Keycloak: un productor no puede hacerse pasar por ellos. */
    static final String RESERVED_ENTITY = "admin";

    static final Set<String> WARNING_TYPES = Set.of(
            ActivityTypes.USERS_USER_DELETED, ActivityTypes.USERS_USER_DISABLED, ActivityTypes.USERS_USER_PASSWORD_RESET,
            ActivityTypes.USERS_CREDENTIAL_DELETED, ActivityTypes.USERS_SESSION_REVOKED, ActivityTypes.USERS_SESSION_ALL_REVOKED,
            ActivityTypes.USERS_OFFLINE_SESSION_REVOKED, ActivityTypes.USERS_OFFLINE_SESSION_ALL_REVOKED);

    /**
     * Lo que se guarda de {@code values}, por lista blanca. {@code temporaryCredential} se guarda
     * como {@code temporaryAccess} y el id de una credencial retirada no se guarda: el saneador
     * del registro tira cualquier clave que suene a credencial, y aqui solo interesa su {@code type}.
     */
    static final List<String> VALUE_KEYS = List.of("enabled", "requiredActions", "fields", "temporary", "actions", "clientId",
            "client", "roles", "profile", "session", "sessions", "type");
    static final String TEMPORARY_CREDENTIAL_KEY = "temporaryCredential";
    static final String TEMPORARY_ACCESS_KEY = "temporaryAccess";

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersSourceAdapter.class);

    private final ActivityIngestor activityIngestor;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        DomainEventReader event = DomainEventReader.read(envelope, "Users");
        if (RESERVED_ENTITY.equals(event.entityName())) {
            throw new UnprocessableSourceEventException("users.admin.* is reserved for Keycloak's own administration events");
        }
        String type = event.type(ActivityCategory.USERS);
        if (!ActivityTypes.isKnown(type)) {
            LOGGER.warn("Users event without a rule in the catalogue, recorded anyway: {}", type);
        }

        PayloadReader values = event.values();
        String targetUserId = values.string("targetUserId") != null ? values.string("targetUserId") : event.entityId();
        String targetUsername = values.string("targetUsername");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", event.entityName());
        payload.put("eventName", event.eventName());
        payload.put("targetUserId", targetUserId);
        if (targetUsername != null) {
            payload.put("targetUsername", targetUsername);
        }
        if (values.all().get(TEMPORARY_CREDENTIAL_KEY) != null) {
            payload.put(TEMPORARY_ACCESS_KEY, values.all().get(TEMPORARY_CREDENTIAL_KEY));
        }
        for (String key : VALUE_KEYS) {
            Object value = values.all().get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }

        activityIngestor.ingest(ActivityEventDraft.builder()
                .source(event.origin(DEFAULT_ORIGIN), event.sourceEventId())
                .type(type)
                .severity(WARNING_TYPES.contains(type) ? ActivitySeverity.WARNING : ActivitySeverity.INFO)
                .occurredAt(event.occurredAt())
                .actor(event.actor())
                .subject(Subject.of(SUBJECT_TYPE, targetUserId, targetUsername))
                .correlationId(event.correlationId())
                .payload(payload)
                .build());
    }
}
