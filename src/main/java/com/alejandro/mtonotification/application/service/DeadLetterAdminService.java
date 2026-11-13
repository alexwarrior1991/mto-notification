package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.admin.DeadLetterPeekResponse;
import com.alejandro.mtonotification.application.dto.admin.DeadLetterQueuesResponse;
import com.alejandro.mtonotification.application.dto.admin.DeadLetterReprocessResponse;

/**
 * Administracion de las DLQ de las fuentes: cuantos mensajes guardan, cuales son y volver a
 * procesarlos. Una fuente desconocida es 404 {@code SRC-404}; el broker apagado o una DLQ que no
 * existe, 409 {@code SRC-409}; el broker que no responde, 503 {@code SRC-503}; un {@code max} fuera
 * de rango, 400 {@code VAL-001}.
 */
public interface DeadLetterAdminService {

    /** Lo que guarda la DLQ de cada fuente. Con el broker apagado no falla: lo dice. */
    DeadLetterQueuesResponse queues();

    /** Los primeros {@code max} mensajes (20 si no se dice) de la DLQ de una fuente, sin sacarlos. */
    DeadLetterPeekResponse peek(String source, Integer max);

    /** Vuelve a procesar hasta {@code max} mensajes (20 si no se dice) de la DLQ de una fuente. */
    DeadLetterReprocessResponse reprocess(String source, Integer max);
}
