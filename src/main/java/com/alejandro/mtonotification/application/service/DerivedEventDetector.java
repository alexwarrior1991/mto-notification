package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;

/**
 * Mira cada linea recien escrita y decide si de ella sale otra (una racha de fallos de acceso).
 * Corre en la misma transaccion, y el derivado entra por el mismo {@link ActivityIngestor}, asi que
 * es idempotente por construccion: su {@code sourceEventId} es lo que evita que el cuarto fallo
 * vuelva a disparar.
 */
public interface DerivedEventDetector {

    void afterIngested(ActivityEvent event, ActivityEventDraft draft);
}
