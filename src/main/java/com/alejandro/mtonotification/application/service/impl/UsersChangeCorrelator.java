package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.DerivedEventDetector;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivityCategory;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.ActorKind;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Un cambio hecho desde mto-users llega dos veces: por el evento de mto-users, con la persona, y
 * por el evento de administracion de Keycloak, con la cuenta de servicio {@code mto-users-svc}.
 * El segundo no dice nada que el primero no diga mejor, asi que se marca con {@code superseded_by}
 * apuntando al primero y las consultas lo esconden salvo que se pida. Se funden los que hablan del
 * mismo usuario (o de la misma sesion), de una accion equivalente y a menos de
 * {@code app.notification.users.correlation-window} uno del otro, en cualquier orden de llegada:
 * lo habitual es que el de mto-users llegue primero (el lector de Keycloak sondea cada 20 s).
 *
 * <p>Un evento de administracion hecho desde fuera de la aplicacion (la consola, {@code kcadm})
 * tiene actor {@code PERSON} y nunca se funde: es precisamente lo que la regla
 * {@code users-change-outside-application} quiere ensenar. Corre en la transaccion de la ingesta,
 * con una actualizacion condicional ({@code where superseded_by is null}): dos instancias no
 * pisan lo que la otra decidio.</p>
 */
@Service
class UsersChangeCorrelator implements DerivedEventDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsersChangeCorrelator.class);

    static final String KEYCLOAK_ADMIN_SOURCE = KeycloakAdminEventAdapter.SOURCE_SERVICE;
    static final String ADMIN_TYPE_PREFIX = "users.admin.";
    static final String USER_SUBJECT = "user";
    static final String SESSION_SUBJECT = "session";
    static final String SESSION_PAYLOAD_KEY = "session";

    /** Que dice mto-users cuando Keycloak dice cada cosa. Una sesion cerrada desde la aplicacion es la misma sesion que Keycloak borro. */
    static final Map<String, Set<String>> EQUIVALENTS = Map.ofEntries(
            Map.entry(ActivityTypes.USERS_ADMIN_USER_CREATED, Set.of(ActivityTypes.USERS_USER_CREATED)),
            Map.entry(ActivityTypes.USERS_ADMIN_USER_UPDATED, Set.of(ActivityTypes.USERS_USER_UPDATED,
                    ActivityTypes.USERS_USER_ENABLED, ActivityTypes.USERS_USER_DISABLED)),
            Map.entry(ActivityTypes.USERS_ADMIN_USER_DELETED, Set.of(ActivityTypes.USERS_USER_DELETED)),
            Map.entry(ActivityTypes.USERS_ADMIN_PASSWORD_RESET, Set.of(ActivityTypes.USERS_USER_PASSWORD_RESET)),
            Map.entry(ActivityTypes.USERS_ADMIN_ACTIONS_EMAIL_SENT, Set.of(ActivityTypes.USERS_USER_ACTIONS_EMAIL_SENT)),
            Map.entry(ActivityTypes.USERS_ADMIN_LOGOUT, Set.of(ActivityTypes.USERS_SESSION_ALL_REVOKED)),
            Map.entry(ActivityTypes.USERS_ADMIN_SESSION_DELETED, Set.of(ActivityTypes.USERS_SESSION_REVOKED,
                    ActivityTypes.USERS_OFFLINE_SESSION_REVOKED)),
            Map.entry(ActivityTypes.USERS_ADMIN_CONSENT_REVOKED, Set.of(ActivityTypes.USERS_OFFLINE_SESSION_ALL_REVOKED)),
            Map.entry(ActivityTypes.USERS_ADMIN_CREDENTIAL_DELETED, Set.of(ActivityTypes.USERS_CREDENTIAL_DELETED)),
            Map.entry(ActivityTypes.USERS_ADMIN_CLIENT_ROLES_ADDED, Set.of(ActivityTypes.USERS_CLIENT_ROLES_ADDED)),
            Map.entry(ActivityTypes.USERS_ADMIN_CLIENT_ROLES_REMOVED, Set.of(ActivityTypes.USERS_CLIENT_ROLES_REMOVED)),
            Map.entry(ActivityTypes.USERS_ADMIN_REALM_ROLES_ADDED, Set.of(ActivityTypes.USERS_PROFILE_ASSIGNED)),
            Map.entry(ActivityTypes.USERS_ADMIN_REALM_ROLES_REMOVED, Set.of(ActivityTypes.USERS_PROFILE_REMOVED)));

    private static final Map<String, Set<String>> ADMIN_TYPES_BY_USERS_TYPE = invert(EQUIVALENTS);

    private final ActivityEventRepository repository;
    private final NotificationProperties properties;

    UsersChangeCorrelator(ActivityEventRepository repository, NotificationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void afterIngested(ActivityEvent event, ActivityEventDraft draft) {
        if (event.getCategory() != ActivityCategory.USERS || event.getType() == null) {
            return;
        }
        if (event.getType().startsWith(ADMIN_TYPE_PREFIX)) {
            if (KEYCLOAK_ADMIN_SOURCE.equals(event.getSourceService()) && event.getActorKind() == ActorKind.SERVICE) {
                supersedeThisKeycloakLine(event);
            }
            return;
        }
        supersedeEarlierKeycloakLines(event, draft);
    }

    /** La linea de Keycloak llego despues: si mto-users ya conto este cambio, esta queda detras de aquella. */
    private void supersedeThisKeycloakLine(ActivityEvent keycloakLine) {
        Set<String> usersTypes = EQUIVALENTS.get(keycloakLine.getType());
        if (usersTypes == null || keycloakLine.getSubjectId() == null) {
            return;
        }
        Window window = window(keycloakLine.getOccurredAt());
        List<ActivityEvent> candidates = SESSION_SUBJECT.equals(keycloakLine.getSubjectType())
                ? repository.findLinesBySessionPayload(usersTypes, keycloakLine.getSubjectId(), window.from(), window.to())
                : USER_SUBJECT.equals(keycloakLine.getSubjectType())
                ? repository.findLinesBySubject(usersTypes, USER_SUBJECT, keycloakLine.getSubjectId(), window.from(), window.to())
                : List.of();
        Optional<ActivityEvent> closest = candidates.stream()
                .filter(candidate -> !candidate.getId().equals(keycloakLine.getId()))
                .min(Comparator.comparing(candidate -> Duration.between(candidate.getOccurredAt(), keycloakLine.getOccurredAt()).abs()));
        closest.ifPresent(usersLine -> supersede(keycloakLine, usersLine));
    }

    /** La linea de mto-users llego: toda linea de Keycloak del mismo cambio que ya estaba queda detras de ella. */
    private void supersedeEarlierKeycloakLines(ActivityEvent usersLine, ActivityEventDraft draft) {
        Set<String> adminTypes = ADMIN_TYPES_BY_USERS_TYPE.get(usersLine.getType());
        if (adminTypes == null) {
            return;
        }
        Window window = window(usersLine.getOccurredAt());
        List<ActivityEvent> candidates = new ArrayList<>();
        if (usersLine.getSubjectId() != null) {
            candidates.addAll(repository.findUnsupersededLines(KEYCLOAK_ADMIN_SOURCE, ActorKind.SERVICE, adminTypes,
                    USER_SUBJECT, usersLine.getSubjectId(), window.from(), window.to()));
        }
        Object session = draft.payload() == null ? null : draft.payload().get(SESSION_PAYLOAD_KEY);
        if (session != null && !session.toString().isBlank()) {
            candidates.addAll(repository.findUnsupersededLines(KEYCLOAK_ADMIN_SOURCE, ActorKind.SERVICE, adminTypes,
                    SESSION_SUBJECT, session.toString().trim(), window.from(), window.to()));
        }
        for (ActivityEvent keycloakLine : new LinkedHashSet<>(candidates)) {
            supersede(keycloakLine, usersLine);
        }
    }

    private void supersede(ActivityEvent keycloakLine, ActivityEvent usersLine) {
        if (repository.supersede(keycloakLine.getId(), usersLine.getId()) == 1) {
            LOGGER.info("Keycloak admin event {} ({}) superseded by mto-users event {} ({})",
                    keycloakLine.getId(), keycloakLine.getType(), usersLine.getId(), usersLine.getType());
        }
    }

    private Window window(Instant around) {
        Duration half = properties.users().correlationWindow();
        return new Window(around.minus(half), around.plus(half));
    }

    private static Map<String, Set<String>> invert(Map<String, Set<String>> equivalents) {
        Map<String, Set<String>> inverted = new HashMap<>();
        equivalents.forEach((adminType, usersTypes) -> usersTypes.forEach(usersType ->
                inverted.computeIfAbsent(usersType, ignored -> new LinkedHashSet<>()).add(adminType)));
        Map<String, Set<String>> frozen = new HashMap<>();
        inverted.forEach((usersType, adminTypes) -> frozen.put(usersType, Set.copyOf(adminTypes)));
        return Map.copyOf(frozen);
    }

    private record Window(Instant from, Instant to) {
    }
}
