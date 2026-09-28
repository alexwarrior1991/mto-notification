package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.application.service.SourceEventHandler;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Une el inbox y el reparto por fuente: el adaptador corre una vez por mensaje, siempre. No es
 * transaccional a proposito: cuando el {@code catch} se ejecuta, la transaccion del intento ya ha
 * revertido y soltado sus bloqueos, y solo entonces se puede escribir el fallo. La excepcion se
 * relanza siempre: el contenedor decide reintentar o mandar a la DLQ.
 */
@Service
@RequiredArgsConstructor
class IdempotentSourceEventProcessor implements SourceEventProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentSourceEventProcessor.class);

    private final InboxMessageService inboxMessageService;
    private final SourceEventHandler sourceEventHandler;

    @Override
    public InboxProcessingResult process(InboxMessageCommand command, SourceEnvelope envelope, SourceEventContext context) {
        try {
            return inboxMessageService.process(command, () -> sourceEventHandler.handle(envelope, context));
        } catch (RuntimeException failure) {
            LOGGER.error("Source message failed and was recorded as failed in the inbox: messageId={}, sourceService={}, "
                    + "source={}, eventType={}", command.messageId(), command.sourceService(), context.sourceId(),
                    command.eventType(), failure);
            inboxMessageService.recordFailure(command, failure);
            throw failure;
        }
    }
}
