package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.deadletter.DeadLetter;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterQueueDepth;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterReprocessing;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterReprocessing.StoppedBy;
import com.alejandro.mtonotification.application.dto.deadletter.ReprocessedDeadLetter;
import com.alejandro.mtonotification.application.dto.deadletter.ReprocessedDeadLetter.Outcome;
import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.exception.BrokerUnavailableException;
import com.alejandro.mtonotification.application.exception.ConflictException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.service.DeadLetterQueues;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import com.alejandro.mtonotification.configuration.rabbitmq.SourceRabbitProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.amqp.rabbit.support.MessagePropertiesConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Las DLQ de las fuentes sobre RabbitMQ. Mirar y reprocesar hacen lo mismo con el broker: sacan
 * mensajes de la DLQ con {@code basic.get} <b>sin confirmarlos</b>, en un canal propio, y solo al
 * final devuelven a la cola los que no se confirmaron.
 *
 * <p>De ahi salen las garantias del reproceso, sin publicar nada (este servicio solo consume):</p>
 * <ul>
 *   <li><b>Nada se pierde</b>: un mensaje solo se confirma despues de que el inbox lo haya aplicado y
 *       su transaccion haya terminado. Si el proceso muere a mitad, el broker devuelve a la DLQ todo
 *       lo no confirmado, y el que ya se habia aplicado sale la proxima vez como duplicado.</li>
 *   <li><b>Nada gira</b>: mientras el canal lo tiene sin confirmar, un mensaje no se vuelve a entregar,
 *       asi que cada pasada ve cada mensaje una sola vez. El que falla otra vez vuelve a la DLQ al
 *       terminar la pasada, no antes: devolverlo en el momento lo pondria otra vez el primero.</li>
 *   <li><b>La puerta es la misma</b>: cada mensaje pasa por {@link SourceEventConsumer}, como una
 *       entrega normal: la firma sobre los bytes, el identificador, el inbox y el adaptador. El que
 *       murio por su firma vuelve a morir por ella.</li>
 *   <li><b>Sin transaccion abierta</b>: aqui no hay ninguna; la del inbox empieza y termina dentro de
 *       cada mensaje.</li>
 * </ul>
 */
public class RabbitDeadLetterQueues implements DeadLetterQueues {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitDeadLetterQueues.class);

    static final String X_DEATH = "x-death";
    private static final int MAX_REASON_LENGTH = 500;

    private final ConnectionFactory connectionFactory;
    private final AmqpAdmin amqpAdmin;
    private final SourceRabbitProperties properties;
    private final MessageConverter messageConverter;
    private final SourceEventProcessor processor;
    private final MessagePayloadSignatureVerifier signatureVerifier;
    private final MessagePropertiesConverter propertiesConverter = new DefaultMessagePropertiesConverter();

    public RabbitDeadLetterQueues(ConnectionFactory connectionFactory, AmqpAdmin amqpAdmin, SourceRabbitProperties properties,
                                  MessageConverter messageConverter, SourceEventProcessor processor,
                                  MessagePayloadSignatureVerifier signatureVerifier) {
        this.connectionFactory = connectionFactory;
        this.amqpAdmin = amqpAdmin;
        this.properties = properties;
        this.messageConverter = messageConverter;
        this.processor = processor;
        this.signatureVerifier = signatureVerifier;
    }

    @Override
    public List<DeadLetterQueueDepth> depths() {
        List<DeadLetterQueueDepth> depths = new ArrayList<>();
        for (String source : new TreeSet<>(properties.sources().keySet())) {
            depths.add(depth(source));
        }
        return depths;
    }

    @Override
    public DeadLetterQueueDepth depth(String source) {
        String id = normalize(source);
        SourceRabbitProperties.Source configured = source(id);
        QueueInformation information;
        try {
            information = amqpAdmin.getQueueInfo(configured.deadLetterQueue());
        } catch (AmqpException unreachable) {
            throw unavailable(unreachable);
        }
        return new DeadLetterQueueDepth(id, configured.queue(), configured.deadLetterQueue(),
                information == null ? null : (long) information.getMessageCount());
    }

    @Override
    public List<DeadLetter> peek(String source, int max) {
        String id = normalize(source);
        SourceRabbitProperties.Source configured = existing(id);
        return withUnacknowledged(configured, session -> {
            List<DeadLetter> peeked = new ArrayList<>();
            while (peeked.size() < max) {
                GetResponse response = session.next();
                if (response == null) {
                    break;
                }
                peeked.add(describe(id, message(response, configured)));
            }
            return peeked;
        });
    }

    @Override
    public DeadLetterReprocessing reprocess(String source, int max, Duration timeBudget) {
        String id = normalize(source);
        SourceRabbitProperties.Source configured = existing(id);
        SourceEventConsumer consumer = new SourceEventConsumer(id, processor, signatureVerifier);
        long deadline = System.nanoTime() + timeBudget.toNanos();
        return withUnacknowledged(configured, session -> {
            List<ReprocessedDeadLetter> results = new ArrayList<>();
            while (true) {
                if (results.size() >= max) {
                    return new DeadLetterReprocessing(results, StoppedBy.MAX);
                }
                if (System.nanoTime() - deadline >= 0) {
                    return new DeadLetterReprocessing(results, StoppedBy.TIME_BUDGET);
                }
                GetResponse response = session.next();
                if (response == null) {
                    return new DeadLetterReprocessing(results, StoppedBy.EMPTY);
                }
                Message message = message(response, configured);
                ReprocessedDeadLetter result = reprocess(consumer, message);
                if (result.outcome() != Outcome.FAILED) {
                    // Solo ahora, con el inbox ya confirmado: si este ack se pierde, el mensaje sigue en la
                    // DLQ y la proxima pasada lo saca como duplicado.
                    session.acknowledge(response);
                }
                results.add(result);
            }
        });
    }

    /** Una entrega normal, con su conversion: lo que lance es el motivo por el que el mensaje se queda. */
    private ReprocessedDeadLetter reprocess(SourceEventConsumer consumer, Message message) {
        MessageProperties messageProperties = message.getMessageProperties();
        String eventType = header(messageProperties, SourceMessageHeaders.EVENT_TYPE);
        String messageId = messageProperties.getMessageId();
        try {
            SourceEnvelope envelope = envelope(message);
            if (envelope.operationId() != null) {
                messageId = envelope.operationId().toString();
            }
            if (eventType == null) {
                eventType = envelope.eventType();
            }
            InboxProcessingResult result = consumer.consume(envelope, message);
            return new ReprocessedDeadLetter(messageId, eventType, messageProperties.getReceivedRoutingKey(),
                    result == InboxProcessingResult.PROCESSED ? Outcome.PROCESSED : Outcome.DUPLICATE, null);
        } catch (RuntimeException failure) {
            LOGGER.warn("Dead letter failed again and stays in its queue: source={}, messageId={}, reason={}",
                    consumer.sourceId(), messageId, failure.toString());
            return new ReprocessedDeadLetter(messageId, eventType, messageProperties.getReceivedRoutingKey(), Outcome.FAILED,
                    reason(failure));
        }
    }

    private DeadLetter describe(String source, Message message) {
        MessageProperties messageProperties = message.getMessageProperties();
        Map<String, Object> death = death(messageProperties, null);
        String messageId = messageProperties.getMessageId();
        String sourceService = null;
        String eventType = header(messageProperties, SourceMessageHeaders.EVENT_TYPE);
        String aggregateType = header(messageProperties, SourceMessageHeaders.AGGREGATE_TYPE);
        String aggregateId = header(messageProperties, SourceMessageHeaders.AGGREGATE_ID);
        String problem = signatureVerifier.rejectionReason(message.getBody(),
                        header(messageProperties, SourceMessageHeaders.SIGNATURE),
                        header(messageProperties, SourceMessageHeaders.SIGNATURE_ALGORITHM))
                .map(reason -> "it would be rejected because " + reason)
                .orElse(null);
        try {
            SourceEnvelope envelope = envelope(message);
            sourceService = envelope.origin();
            if (envelope.operationId() != null) {
                messageId = envelope.operationId().toString();
            }
            if (envelope.data() == null) {
                problem = problem == null ? "it has no 'data' payload" : problem;
            } else {
                InboxMessageCommand command = SourceMessageCommandFactory.from(envelope, message);
                messageId = command.messageId();
                sourceService = command.sourceService();
                eventType = eventType == null ? command.eventType() : eventType;
                aggregateType = aggregateType == null ? command.aggregateType() : aggregateType;
                aggregateId = aggregateId == null ? command.aggregateId() : aggregateId;
            }
        } catch (RuntimeException unreadable) {
            problem = problem == null ? reason(unreadable) : problem;
        }
        LOGGER.debug("Dead letter peeked: source={}, messageId={}", source, messageId);
        return new DeadLetter(messageId, sourceService, eventType, aggregateType, aggregateId,
                messageProperties.getReceivedExchange(), messageProperties.getReceivedRoutingKey(),
                text(death.get("reason")), number(death.get("count")), instant(death.get("time")),
                message.getBody().length, problem);
    }

    /**
     * El sobre, con el mismo convertidor que la escucha y el mismo tipo destino. Un cuerpo que no es
     * JSON no lanza siempre: el convertidor puede devolver los bytes tal cual.
     */
    private SourceEnvelope envelope(Message message) {
        message.getMessageProperties().setInferredArgumentType(SourceEnvelope.class);
        Object converted = messageConverter.fromMessage(message);
        if (converted instanceof SourceEnvelope envelope) {
            return envelope;
        }
        throw new IllegalArgumentException("The message body is not the JSON envelope of a source (content type "
                + message.getMessageProperties().getContentType() + ")");
    }

    /**
     * El mensaje tal como lo vio la cola de trabajo. El broker lo reenvio por el DLX con la clave de
     * la DLQ, y lo que el inbox guarda (exchange, clave de enrutado, cola) es lo original, que viaja
     * en {@code x-death}. La firma no depende de nada de esto: va sobre el cuerpo, que no cambia.
     */
    private Message message(GetResponse response, SourceRabbitProperties.Source configured) {
        MessageProperties messageProperties = propertiesConverter.toMessageProperties(response.getProps(), response.getEnvelope(),
                StandardCharsets.UTF_8.name());
        Map<String, Object> death = death(messageProperties, configured.queue());
        if (text(death.get("exchange")) != null) {
            messageProperties.setReceivedExchange(text(death.get("exchange")));
        }
        if (death.get("routing-keys") instanceof List<?> routingKeys && !routingKeys.isEmpty() && routingKeys.getFirst() != null) {
            messageProperties.setReceivedRoutingKey(routingKeys.getFirst().toString());
        }
        messageProperties.setConsumerQueue(configured.queue());
        return new Message(response.getBody(), messageProperties);
    }

    /** La muerte en {@code queue} o, si no hay ninguna de esa cola (o no se pide), la mas reciente. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> death(MessageProperties messageProperties, String queue) {
        if (!(messageProperties.getHeader(X_DEATH) instanceof List<?> deaths)) {
            return Map.of();
        }
        Map<String, Object> first = Map.of();
        for (Object entry : deaths) {
            if (entry instanceof Map<?, ?> death) {
                if (queue == null || queue.equals(text(death.get("queue")))) {
                    return (Map<String, Object>) death;
                }
                first = first.isEmpty() ? (Map<String, Object>) death : first;
            }
        }
        return first;
    }

    /**
     * Abre un canal propio, deja trabajar con mensajes sin confirmar y, pase lo que pase, devuelve a
     * la cola los que sigan sin confirmar. Se devuelven de forma explicita porque el canal de un
     * {@code CachingConnectionFactory} no se cierra al soltarlo: vuelve a su cache, con lo pendiente.
     */
    private <T> T withUnacknowledged(SourceRabbitProperties.Source configured, SessionWork<T> work) {
        Connection connection = null;
        Channel channel = null;
        try {
            connection = connectionFactory.createConnection();
            channel = connection.createChannel(false);
            Session session = new Session(channel, configured.deadLetterQueue());
            try {
                return work.with(session);
            } finally {
                session.returnUnacknowledged();
            }
        } catch (AmqpException | IOException unreachable) {
            throw unavailable(unreachable);
        } finally {
            RabbitUtils.closeChannel(channel);
            RabbitUtils.closeConnection(connection);
        }
    }

    private SourceRabbitProperties.Source existing(String source) {
        DeadLetterQueueDepth depth = depth(source);
        if (depth.messages() == null) {
            throw new ConflictException("Source", "The dead letter queue " + depth.deadLetterQueue() + " of source '" + source
                    + "' does not exist in the broker");
        }
        return source(source);
    }

    private SourceRabbitProperties.Source source(String source) {
        SourceRabbitProperties.Source configured = properties.sources().get(source);
        if (configured == null) {
            throw NotFoundException.of("Source", "There is no RabbitMQ source '" + source + "'; the sources are "
                    + new TreeSet<>(properties.sources().keySet()));
        }
        return configured;
    }

    private static String normalize(String source) {
        return source == null ? "" : source.trim().toLowerCase();
    }

    private static BrokerUnavailableException unavailable(Exception cause) {
        return new BrokerUnavailableException("RabbitMQ is not answering: " + cause.getMessage(), cause);
    }

    private static String header(MessageProperties messageProperties, String name) {
        return text(messageProperties.getHeader(name));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant instant(Object value) {
        return value instanceof Date date ? date.toInstant() : null;
    }

    private static String reason(Throwable failure) {
        String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    @FunctionalInterface
    private interface SessionWork<T> {
        T with(Session session) throws IOException;
    }

    /** Un canal sobre una DLQ: saca sin confirmar, confirma de uno en uno y devuelve el resto de una vez. */
    private static final class Session {

        private final Channel channel;
        private final String queue;
        private final TreeSet<Long> unacknowledged = new TreeSet<>();

        private Session(Channel channel, String queue) {
            this.channel = channel;
            this.queue = queue;
        }

        private GetResponse next() throws IOException {
            GetResponse response = channel.basicGet(queue, false);
            if (response != null) {
                unacknowledged.add(response.getEnvelope().getDeliveryTag());
            }
            return response;
        }

        private void acknowledge(GetResponse response) throws IOException {
            channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
            unacknowledged.remove(response.getEnvelope().getDeliveryTag());
        }

        /**
         * Un solo {@code nack} multiple hasta la ultima entrega pendiente: los ya confirmados no
         * cuentan. Solo si queda alguna, porque un {@code nack} de una entrega que el broker no tiene
         * pendiente cierra el canal. Si falla, el canal esta roto y el broker devuelve lo pendiente al
         * cerrarlo.
         */
        private void returnUnacknowledged() {
            if (unacknowledged.isEmpty() || !channel.isOpen()) {
                return;
            }
            try {
                channel.basicNack(unacknowledged.last(), true, true);
                unacknowledged.clear();
            } catch (IOException | RuntimeException broken) {
                LOGGER.warn("Could not return the unacknowledged dead letters of {}; the broker returns them when the channel closes: {}",
                        queue, broken.toString());
                try {
                    channel.abort();
                } catch (IOException | RuntimeException ignored) {
                    // ya estaba cerrado
                }
            }
        }
    }
}
