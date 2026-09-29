package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;

/**
 * Lo unico que un consumidor de RabbitMQ conoce: entrega el sobre con sus metadatos y recibe que
 * se hizo con el. Que la idempotencia la de el inbox y que el trabajo lo haga el adaptador de la
 * fuente son detalles de la implementacion.
 */
public interface SourceEventProcessor {

    InboxProcessingResult process(InboxMessageCommand command, SourceEnvelope envelope, SourceEventContext context);
}
