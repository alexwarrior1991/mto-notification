package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Lo comun a los consumidores de todas las fuentes: comprobar la firma sobre los bytes que
 * viajaron, rechazar sin reintentos lo que no mejora por reintentarse (sin payload, sin
 * identificador, un adaptador que dice que no lo entiende) y entregar al inbox lo demas. Nunca se
 * traga una excepcion: un {@code catch} que solo registrase confirmaria al broker un mensaje que no
 * se aplico.
 */
public class SourceEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceEventConsumer.class);

    private final String sourceId;
    private final SourceEventProcessor processor;
    private final MessagePayloadSignatureVerifier signatureVerifier;

    public SourceEventConsumer(String sourceId, SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.sourceId = sourceId;
        this.processor = processor;
        this.signatureVerifier = signatureVerifier;
    }

    public String sourceId() {
        return sourceId;
    }

    public InboxProcessingResult consume(SourceEnvelope envelope, Message rawMessage) {
        MessageProperties properties = rawMessage.getMessageProperties();
        LOGGER.info("Source message received: source={}, exchange={}, routingKey={}, queue={}, messageId={}, eventType={}, "
                        + "aggregateType={}, aggregateId={}, sequenceNumber={}, signatureAlgorithm={}",
                sourceId, properties.getReceivedExchange(), properties.getReceivedRoutingKey(), properties.getConsumerQueue(),
                properties.getMessageId(), properties.getHeader(SourceMessageHeaders.EVENT_TYPE),
                properties.getHeader(SourceMessageHeaders.AGGREGATE_TYPE), properties.getHeader(SourceMessageHeaders.AGGREGATE_ID),
                properties.getHeader(SourceMessageHeaders.SEQUENCE_NUMBER), properties.getHeader(SourceMessageHeaders.SIGNATURE_ALGORITHM));

        rejectIfNotAuthentic(rawMessage, properties);
        if (envelope == null || envelope.data() == null) {
            throw unprocessable("has no 'data' payload", properties);
        }

        InboxMessageCommand command = SourceMessageCommandFactory.from(envelope, rawMessage);
        SourceEventContext context = new SourceEventContext(sourceId, command.sequenceNumber(), properties.getReceivedRoutingKey());
        InboxProcessingResult result;
        try {
            result = processor.process(command, envelope, context);
        } catch (UnprocessableSourceEventException permanent) {
            // El inbox ya lo dejo FAILED con el motivo; a la DLQ sin gastar intentos.
            throw unprocessable("cannot be interpreted (" + permanent.getMessage() + ")", properties);
        }
        LOGGER.info("Source message settled: source={}, messageId={}, idempotencyKey={}, result={}",
                sourceId, properties.getMessageId(), command.messageId(), result);
        return result;
    }

    private void rejectIfNotAuthentic(Message rawMessage, MessageProperties properties) {
        signatureVerifier.rejectionReason(rawMessage.getBody(),
                        header(properties, SourceMessageHeaders.SIGNATURE),
                        header(properties, SourceMessageHeaders.SIGNATURE_ALGORITHM))
                .ifPresent(reason -> {
                    throw unprocessable("was rejected because " + reason, properties);
                });
    }

    private static String header(MessageProperties properties, String name) {
        Object value = properties.getHeader(name);
        return value == null ? null : value.toString();
    }

    private AmqpRejectAndDontRequeueException unprocessable(String reason, MessageProperties properties) {
        LOGGER.error("Source message sent to the dead letter queue, it {}: source={}, messageId={}, exchange={}, routingKey={}",
                reason, sourceId, properties.getMessageId(), properties.getReceivedExchange(), properties.getReceivedRoutingKey());
        return new AmqpRejectAndDontRequeueException("Source message " + reason + ": source=" + sourceId
                + ", messageId=" + properties.getMessageId());
    }
}
