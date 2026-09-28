package com.alejandro.mtonotification.application.dto.messaging;

import com.alejandro.mtonotification.domain.model.Actor;
import com.alejandro.mtonotification.domain.model.ActorKind;

/**
 * El {@code actor} del sobre comun de los productores del dominio: quien hizo lo que el evento
 * cuenta. Es opcional en el contrato (mto-configuration lo anade en la fase 2b).
 *
 * @param id       el {@code sub} del token
 * @param username el {@code preferred_username}
 * @param kind     {@code PERSON}, {@code SERVICE} o {@code SYSTEM}, tal como lo clasifico el emisor
 */
public record SourceActor(String id, String username, String kind) {

    public Actor toActor() {
        ActorKind actorKind = parseKind();
        if (actorKind == null) {
            return Actor.ofUsername(username, id);
        }
        return new Actor(actorKind, username, id);
    }

    private ActorKind parseKind() {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return ActorKind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
