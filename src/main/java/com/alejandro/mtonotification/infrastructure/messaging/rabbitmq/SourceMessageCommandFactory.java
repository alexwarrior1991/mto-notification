package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.domain.model.Fingerprints;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * De una entrega AMQP al comando sin transporte del inbox. La clave de idempotencia es el
 * {@code operationId} del sobre o, si falta, el {@code message_id} de AMQP; sin ninguno de los dos
 * el mensaje se rechaza sin reintentos, porque aplicarlo «de todas formas» romperia la promesa de
 * exactamente-una-vez en silencio.
 */
public final class SourceMessageCommandFactory {

    static final String UNKNOWN_SOURCE_SERVICE = "unknown";

    private static final int MAX_MESSAGE_ID_LENGTH = 200;
    private static final int MAX_SOURCE_SERVICE_LENGTH = 100;
    private static final int MAX_EVENT_TYPE_LENGTH = 150;
    private static final int MAX_AGGREGATE_TYPE_LENGTH = 150;
    private static final int MAX_AGGREGATE_ID_LENGTH = 100;
    private static final int MAX_ROUTING_LENGTH = 255;

    private SourceMessageCommandFactory() {
    }

    public static InboxMessageCommand from(SourceEnvelope envelope, Message rawMessage) {
        MessageProperties properties = rawMessage.getMessageProperties();
        String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        Map<String, Object> data = envelope == null || envelope.data() == null ? Map.of() : envelope.data();
        return new InboxMessageCommand(
                idempotencyKey(envelope, properties),
                truncate(sourceService(envelope), MAX_SOURCE_SERVICE_LENGTH),
                truncate(envelope == null ? null : envelope.eventType(), MAX_EVENT_TYPE_LENGTH),
                truncate(text(data.get("entityName")), MAX_AGGREGATE_TYPE_LENGTH),
                truncate(text(data.get("entityId")), MAX_AGGREGATE_ID_LENGTH),
                truncate(properties.getReceivedExchange(), MAX_ROUTING_LENGTH),
                truncate(properties.getReceivedRoutingKey(), MAX_ROUTING_LENGTH),
                truncate(properties.getConsumerQueue(), MAX_ROUTING_LENGTH),
                Fingerprints.sha256(payload),
                payload,
                sequenceNumber(properties));
    }

    /** AMQP devuelve el entero mas pequeno en el que quepa; un valor ilegible se lee como ausente. */
    static Long sequenceNumber(MessageProperties properties) {
        Object value = properties.getHeader(SourceMessageHeaders.SEQUENCE_NUMBER);
        return switch (value) {
            case null -> null;
            case Number number -> number.longValue();
            default -> parse(value.toString());
        };
    }

    private static Long parse(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    private static String idempotencyKey(SourceEnvelope envelope, MessageProperties properties) {
        String key = envelope != null && envelope.operationId() != null ? envelope.operationId().toString() : properties.getMessageId();
        if (key == null || key.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Message has neither an operationId in the payload nor an AMQP "
                    + "messageId: without a stable identifier the inbox cannot guarantee it is applied exactly once");
        }
        if (key.length() > MAX_MESSAGE_ID_LENGTH) {
            throw new AmqpRejectAndDontRequeueException("Message identifier is longer than " + MAX_MESSAGE_ID_LENGTH + " characters: " + key.length());
        }
        return key;
    }

    private static String sourceService(SourceEnvelope envelope) {
        return envelope == null || envelope.origin() == null || envelope.origin().isBlank() ? UNKNOWN_SOURCE_SERVICE : envelope.origin();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
