package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.admin.DeadLetterPeekResponse;
import com.alejandro.mtonotification.application.dto.admin.DeadLetterPeekResponse.InboxRecord;
import com.alejandro.mtonotification.application.dto.admin.DeadLetterPeekResponse.PeekedDeadLetter;
import com.alejandro.mtonotification.application.dto.admin.DeadLetterQueuesResponse;
import com.alejandro.mtonotification.application.dto.admin.DeadLetterReprocessResponse;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetter;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterQueueDepth;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterReprocessing;
import com.alejandro.mtonotification.application.dto.deadletter.ReprocessedDeadLetter;
import com.alejandro.mtonotification.application.dto.deadletter.ReprocessedDeadLetter.Outcome;
import com.alejandro.mtonotification.application.exception.ConflictException;
import com.alejandro.mtonotification.application.exception.ValidationException;
import com.alejandro.mtonotification.application.service.DeadLetterAdminService;
import com.alejandro.mtonotification.application.service.DeadLetterQueues;
import com.alejandro.mtonotification.configuration.security.CurrentUserService;
import com.alejandro.mtonotification.infrastructure.persistence.repository.InboxMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Las DLQ para quien administra. No es transaccional a proposito: hablar con el broker con una
 * transaccion abierta retendria una conexion del pool mientras se espera a la red, y cada mensaje
 * reprocesado abre y cierra la suya en el inbox. Lo que el inbox sabe de un mensaje mirado se lee
 * despues, con el canal ya cerrado.
 *
 * <p>Una pasada esta acotada dos veces: por el numero de mensajes ({@link #MAX_BATCH}) y por el
 * tiempo ({@link #TIME_BUDGET}, por debajo de los 15 s que el gateway espera a un servicio). Lo que
 * no entra se queda en la DLQ para la siguiente llamada, que es de quien administra: nada aqui se
 * repite solo.</p>
 */
@Service
@RequiredArgsConstructor
class DeadLetterAdminServiceImpl implements DeadLetterAdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterAdminServiceImpl.class);

    static final int DEFAULT_BATCH = 20;
    static final int MAX_BATCH = 100;
    static final Duration TIME_BUDGET = Duration.ofSeconds(10);
    /** {@code mto_notification_dead_letters_reprocessed_total{source, result}}: processed, duplicate, failed. */
    static final String REPROCESSED_METER = "mto.notification.dead.letters.reprocessed";

    private final ObjectProvider<DeadLetterQueues> deadLetterQueues;
    private final InboxMessageRepository inboxMessageRepository;
    private final CurrentUserService currentUser;
    private final MeterRegistry meterRegistry;

    @Override
    public DeadLetterQueuesResponse queues() {
        DeadLetterQueues queues = deadLetterQueues.getIfAvailable();
        return queues == null ? new DeadLetterQueuesResponse(false, List.of()) : new DeadLetterQueuesResponse(true, queues.depths());
    }

    @Override
    public DeadLetterPeekResponse peek(String source, Integer max) {
        DeadLetterQueues queues = enabled();
        int batch = batch(max);
        DeadLetterQueueDepth depth = queues.depth(source);
        List<DeadLetter> peeked = queues.peek(source, batch);
        return new DeadLetterPeekResponse(depth.source(), depth.deadLetterQueue(), depth.messages() == null ? 0 : depth.messages(),
                peeked.stream().map(message -> new PeekedDeadLetter(message, inboxRecord(message))).toList());
    }

    @Override
    public DeadLetterReprocessResponse reprocess(String source, Integer max) {
        DeadLetterQueues queues = enabled();
        int batch = batch(max);
        DeadLetterReprocessing reprocessing = queues.reprocess(source, batch, TIME_BUDGET);
        DeadLetterQueueDepth depth = queues.depth(source);
        for (ReprocessedDeadLetter result : reprocessing.results()) {
            meterRegistry.counter(REPROCESSED_METER, "source", depth.source(), "result", result.outcome().name().toLowerCase()).increment();
        }
        DeadLetterReprocessResponse response = new DeadLetterReprocessResponse(depth.source(), depth.deadLetterQueue(), batch,
                reprocessing.results().size(), reprocessing.count(Outcome.PROCESSED), reprocessing.count(Outcome.DUPLICATE),
                reprocessing.count(Outcome.FAILED), depth.messages(), reprocessing.stoppedBy(), reprocessing.results());
        LOGGER.info("Dead letters reprocessed: source={}, by={}, requested={}, fetched={}, processed={}, duplicates={}, failed={}, "
                        + "remaining={}, stoppedBy={}", response.source(), currentUser.getUsername().orElse("unknown"), batch,
                response.fetched(), response.processed(), response.duplicates(), response.failed(), response.remaining(),
                response.stoppedBy());
        return response;
    }

    private DeadLetterQueues enabled() {
        DeadLetterQueues queues = deadLetterQueues.getIfAvailable();
        if (queues == null) {
            throw new ConflictException("Source", "RabbitMQ is switched off in this deployment (app.rabbitmq.enabled=false): "
                    + "there are no dead letter queues to read");
        }
        return queues;
    }

    private static int batch(Integer max) {
        if (max == null) {
            return DEFAULT_BATCH;
        }
        if (max < 1 || max > MAX_BATCH) {
            throw new ValidationException("max must be between 1 and " + MAX_BATCH + ", was " + max);
        }
        return max;
    }

    private InboxRecord inboxRecord(DeadLetter message) {
        if (message.messageId() == null || message.sourceService() == null) {
            return null;
        }
        return inboxMessageRepository.findByMessageIdAndSourceService(message.messageId(), message.sourceService())
                .map(row -> new InboxRecord(row.getStatus(), row.getProcessingAttempts(), row.getFailedAt(), row.getFailureReason()))
                .orElse(null);
    }
}
