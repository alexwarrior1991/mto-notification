package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.exception.ValidationException;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.infrastructure.persistence.repository.InboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El inbox: registra, reclama y ejecuta el trabajo como mucho una vez. El estado de fallo va en
 * otra transaccion porque {@link #process} revierte al fallar y se lleva la fila; y se escribe
 * desde fuera, porque una transaccion nueva que tocara la fila bloqueada por la del intento se
 * quedaria esperando a una transaccion que espera a que esta devuelva.
 */
@Service
@RequiredArgsConstructor
class InboxMessageServiceImpl implements InboxMessageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxMessageServiceImpl.class);

    private static final int MAX_FAILURE_REASON_LENGTH = 2000;

    private final InboxMessageRepository inboxMessageRepository;

    @Override
    @Transactional
    public InboxProcessingResult process(InboxMessageCommand command, Runnable processing) {
        validate(command);

        inboxMessageRepository.insertIfMissing(
                command.messageId(), command.sourceService(), command.eventType(), command.aggregateType(),
                command.aggregateId(), command.exchangeName(), command.routingKey(), command.queueName(),
                command.payloadHash(), command.payload());

        int claimed = inboxMessageRepository.claimForProcessing(command.messageId(), command.sourceService());
        if (claimed == 0) {
            LOGGER.info("Duplicate message already applied, handler skipped: messageId={}, sourceService={}, eventType={}",
                    command.messageId(), command.sourceService(), command.eventType());
            return InboxProcessingResult.DUPLICATE_SKIPPED;
        }

        processing.run();

        int processed = inboxMessageRepository.markProcessed(command.messageId(), command.sourceService());
        if (processed == 0) {
            throw new IllegalStateException("Inbox message could not be marked as processed: messageId=" + command.messageId());
        }

        LOGGER.debug("Message applied and recorded in the inbox: messageId={}, sourceService={}",
                command.messageId(), command.sourceService());
        return InboxProcessingResult.PROCESSED;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(InboxMessageCommand command, Throwable failure) {
        validate(command);
        inboxMessageRepository.recordFailure(
                command.messageId(), command.sourceService(), command.eventType(), command.aggregateType(),
                command.aggregateId(), command.exchangeName(), command.routingKey(), command.queueName(),
                command.payloadHash(), command.payload(), failureReason(failure));
    }

    private static void validate(InboxMessageCommand command) {
        if (command == null || command.messageId() == null || command.messageId().isBlank()) {
            throw new ValidationException("Inbox message requires a non-blank messageId");
        }
        if (command.sourceService() == null || command.sourceService().isBlank()) {
            throw new ValidationException("Inbox message requires a non-blank sourceService");
        }
        if (command.payload() == null || command.payload().isBlank()) {
            throw new ValidationException("Inbox message requires a payload");
        }
    }

    static String failureReason(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String reason = failure.getClass().getName() + ": " + (failure.getMessage() == null ? "no message" : failure.getMessage());
        return reason.length() <= MAX_FAILURE_REASON_LENGTH ? reason : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
