package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;

import java.util.Optional;

/**
 * La puerta del registro. Escribe la linea si no estaba (insercion condicional) y, solo entonces,
 * pasa el evento por los detectores de derivados y por el motor de reglas. Todo en la transaccion
 * de quien llama: el inbox, el lector o el cerrador de rafagas.
 */
public interface ActivityIngestor {

    /** @return la linea escrita, o vacio si la fuente ya la habia contado. */
    Optional<ActivityEvent> ingest(ActivityEventDraft draft);
}
