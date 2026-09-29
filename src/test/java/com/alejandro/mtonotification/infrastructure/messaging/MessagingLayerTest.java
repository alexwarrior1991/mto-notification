package com.alejandro.mtonotification.infrastructure.messaging;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import com.alejandro.mtonotification.configuration.messaging.MessageSignatureMode;
import com.alejandro.mtonotification.configuration.messaging.MessageSignatureProperties;
import com.alejandro.mtonotification.configuration.messaging.MessagingConfiguration;
import com.alejandro.mtonotification.configuration.rabbitmq.RabbitMqConfiguration;
import com.alejandro.mtonotification.configuration.rabbitmq.SourceRabbitProperties;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.RabbitListenerContainerFactoryNames;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceEventConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceMessageCommandFactory;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceMessageHeaders;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceRabbitMqNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El canal de datos maestros sin broker: el contrato tal como lo publica mto-configuration (el
 * mismo JSON que fija mto-maintenance), el reparto entre consumidor e inbox, la firma y la
 * topologia por fuente.
 */
class MessagingLayerTest {

    /** El mensaje literal de mto-configuration, con las dos claves opcionales de la fase 2b anadidas. */
    private static final String PUBLISHED_MESSAGE_JSON = """
            {
              "operationId": "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10",
              "referenceId": "station-42",
              "origin": "mto-configuration",
              "creationDate": "2026-09-01T10:15:30Z",
              "eventType": "MASTER_DATA_STATION_UPDATED",
              "data": {
                "entityName": "station",
                "entityId": "42",
                "operation": "UPDATED",
                "values": {
                  "code": "BCN-SANTS",
                  "name": "Barcelona Sants",
                  "kp": 3.75
                }
              },
              "messageHash": "9f2c1b0d5a8e7f6c4b3a2d1e0f9c8b7a6d5e4f3c2b1a0918273645546372819a",
              "actor": {"id": "u-1", "username": "config.responsable", "kind": "PERSON"},
              "correlationId": "job-77"
            }""";

    private static final String SECRET = "un-secreto-compartido";

    private static final MessagePayloadSignatureVerifier NO_SIGNATURE_CHECK =
            new MessagePayloadSignatureVerifier(new MessageSignatureProperties(null, MessageSignatureMode.DISABLED));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(RabbitMqConfiguration.class, MessagingConfiguration.class, TestProcessorConfiguration.class)
            .withPropertyValues("spring.rabbitmq.listener.simple.auto-startup=false",
                    "app.rabbitmq.sources.master-data.exchange=" + SourceRabbitMqNames.MASTER_DATA_EXCHANGE,
                    "app.rabbitmq.sources.master-data.routing-key=" + SourceRabbitMqNames.MASTER_DATA_ROUTING_PATTERN,
                    "app.rabbitmq.sources.master-data.queue=" + SourceRabbitMqNames.MASTER_DATA_QUEUE,
                    "app.rabbitmq.sources.master-data.dead-letter-exchange=" + SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.master-data.dead-letter-queue=" + SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.master-data.dead-letter-routing-key=" + SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_ROUTING_KEY,
                    "app.rabbitmq.sources.configuration.exchange=" + SourceRabbitMqNames.CONFIGURATION_EXCHANGE,
                    "app.rabbitmq.sources.configuration.routing-key=" + SourceRabbitMqNames.CONFIGURATION_ROUTING_PATTERN,
                    "app.rabbitmq.sources.configuration.queue=" + SourceRabbitMqNames.CONFIGURATION_QUEUE,
                    "app.rabbitmq.sources.configuration.dead-letter-exchange=" + SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.configuration.dead-letter-queue=" + SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.configuration.dead-letter-routing-key=" + SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_ROUTING_KEY,
                    "app.rabbitmq.sources.users.exchange=" + SourceRabbitMqNames.USERS_EXCHANGE,
                    "app.rabbitmq.sources.users.routing-key=" + SourceRabbitMqNames.USERS_ROUTING_PATTERN,
                    "app.rabbitmq.sources.users.queue=" + SourceRabbitMqNames.USERS_QUEUE,
                    "app.rabbitmq.sources.users.dead-letter-exchange=" + SourceRabbitMqNames.USERS_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.users.dead-letter-queue=" + SourceRabbitMqNames.USERS_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.users.dead-letter-routing-key=" + SourceRabbitMqNames.USERS_DEAD_LETTER_ROUTING_KEY);

    // --- contrato ---

    @Test
    void theMessageOfConfigurationDeserializesWithItsOptionalActorAndCorrelation() {
        SourceEnvelope envelope = convert(PUBLISHED_MESSAGE_JSON);

        assertEquals(UUID.fromString("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10"), envelope.operationId());
        assertEquals("mto-configuration", envelope.origin());
        assertEquals(Instant.parse("2026-09-01T10:15:30Z"), envelope.creationDate());
        assertEquals("station", envelope.data().get("entityName"));
        assertEquals("UPDATED", envelope.data().get("operation"));
        @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) envelope.data().get("values");
        assertEquals("BCN-SANTS", values.get("code"));
        assertEquals("config.responsable", envelope.actor().username());
        assertEquals("job-77", envelope.correlationId());
    }

    @Test
    void aMessageWithoutTheOptionalKeysAndWithUnknownOnesStillDeserializes() {
        String json = PUBLISHED_MESSAGE_JSON
                .replace(",\n  \"actor\": {\"id\": \"u-1\", \"username\": \"config.responsable\", \"kind\": \"PERSON\"},\n  \"correlationId\": \"job-77\"", "")
                .replace("\"referenceId\": \"station-42\"", "\"referenceId\": \"station-42\",\n  \"schemaVersion\": 2");
        SourceEnvelope envelope = convert(json);
        assertNull(envelope.actor());
        assertNull(envelope.correlationId());
        assertEquals("station-42", envelope.referenceId());
    }

    /** Los ejemplos que cada productor versiona en su repositorio, copiados tal cual: el contrato, byte a byte. */
    @Test
    void theExamplesOfEveryProducerDeserializeWithTheirActorAndCorrelation() {
        SourceEnvelope jobFinished = convert(fixture("contracts/mto-configuration/job-finished.json"));
        assertEquals("CONFIGURATION_JOB_FINISHED", jobFinished.eventType());
        assertEquals("job", jobFinished.data().get("entityName"));
        assertEquals("finished", jobFinished.data().get("eventName"));
        assertEquals("config.responsable", jobFinished.actor().username());
        assertEquals("00000000-0000-4000-8000-0000000000aa", jobFinished.correlationId(), "el jobId es la correlacion del trabajo");

        SourceEnvelope steadyArm = convert(fixture("contracts/mto-configuration/master-data-steady-arm-updated.json"));
        assertEquals("steady-arm", steadyArm.data().get("entityName"));
        assertEquals("UPDATED", steadyArm.data().get("operation"));
        assertEquals("config.editor", steadyArm.actor().username());

        SourceEnvelope userCreated = convert(fixture("contracts/mto-users/user-created.json"));
        assertEquals("mto-users", userCreated.origin());
        assertEquals("USERS_USER_CREATED", userCreated.eventType());
        assertEquals("user", userCreated.data().get("entityName"));
        assertEquals("created", userCreated.data().get("eventName"));
        assertEquals("PERSON", userCreated.actor().kind());
        @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) userCreated.data().get("values");
        assertEquals("ana.nueva", values.get("targetUsername"));

        SourceEnvelope profileAssigned = convert(fixture("contracts/mto-users/profile-assigned.json"));
        assertEquals("profile", profileAssigned.data().get("entityName"));
        assertEquals("assigned", profileAssigned.data().get("eventName"));
        @SuppressWarnings("unchecked") Map<String, Object> profileValues = (Map<String, Object>) profileAssigned.data().get("values");
        assertNull(profileValues.get("targetUsername"), "mto-users no siempre sabe el nombre del usuario objetivo");
        assertEquals("mto-users-viewer", profileValues.get("profile"));
    }

    // --- consumidor ---

    @Test
    void theConsumerDelegatesToTheProcessorWithTheSourceContext() {
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        SourceEnvelope envelope = convert(PUBLISHED_MESSAGE_JSON);

        new SourceEventConsumer("master-data", processor, NO_SIGNATURE_CHECK).consume(envelope, rawMessage());

        assertEquals(1, processor.envelopes.size());
        assertEquals("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", processor.commands.getFirst().messageId());
        assertEquals("master-data", processor.contexts.getFirst().sourceId());
        assertEquals(7L, processor.contexts.getFirst().sequenceNumber());
        assertEquals("mto.master-data.station.updated", processor.contexts.getFirst().routingKey());
    }

    @Test
    void aDuplicateIsAcknowledgedAndAMessageWithoutDataOrIdentifierGoesToTheDeadLetterQueue() {
        SourceEnvelope envelope = convert(PUBLISHED_MESSAGE_JSON);
        assertDoesNotThrow(() -> new SourceEventConsumer("master-data", new RecordingProcessor(InboxProcessingResult.DUPLICATE_SKIPPED), NO_SIGNATURE_CHECK)
                .consume(envelope, rawMessage()));

        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        SourceEnvelope withoutData = new SourceEnvelope(UUID.randomUUID(), "x", "mto-configuration", Instant.now(), "X", null, null, null, null);
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new SourceEventConsumer("master-data", processor, NO_SIGNATURE_CHECK).consume(withoutData, rawMessage()));
        SourceEnvelope withoutId = new SourceEnvelope(null, "x", "mto-configuration", Instant.now(), "X", Map.of("entityName", "station"), null, null, null);
        Message withoutMessageId = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8)).andProperties(new MessageProperties()).build();
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new SourceEventConsumer("master-data", processor, NO_SIGNATURE_CHECK).consume(withoutId, withoutMessageId));
        assertTrue(processor.envelopes.isEmpty());
    }

    @Test
    void aPermanentAdapterRejectionGoesToTheDeadLetterQueueAndATransientFailurePropagates() {
        SourceEnvelope envelope = convert(PUBLISHED_MESSAGE_JSON);
        SourceEventProcessor permanent = (command, env, context) -> {
            throw new UnprocessableSourceEventException("no entity");
        };
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new SourceEventConsumer("master-data", permanent, NO_SIGNATURE_CHECK).consume(envelope, rawMessage()));

        SourceEventProcessor transientFailure = (command, env, context) -> {
            throw new IllegalStateException("database is down");
        };
        assertThrows(IllegalStateException.class,
                () -> new SourceEventConsumer("master-data", transientFailure, NO_SIGNATURE_CHECK).consume(envelope, rawMessage()));
    }

    // --- comando ---

    @Test
    void theCommandKeepsTheOriginalBytesAndFallsBackToTheAmqpMessageId() {
        InboxMessageCommand command = SourceMessageCommandFactory.from(convert(PUBLISHED_MESSAGE_JSON), rawMessage());
        assertEquals(PUBLISHED_MESSAGE_JSON, command.payload());
        assertEquals(64, command.payloadHash().length());
        assertEquals("station", command.aggregateType());
        assertEquals("42", command.aggregateId());
        assertEquals(SourceRabbitMqNames.MASTER_DATA_QUEUE, command.queueName());

        SourceEnvelope withoutOperationId = new SourceEnvelope(null, "x", "  ", Instant.now(), "X", Map.of(), null, null, null);
        InboxMessageCommand fallback = SourceMessageCommandFactory.from(withoutOperationId, rawMessage());
        assertEquals("f4b0a1c2-0000-4000-8000-000000000001", fallback.messageId());
        assertEquals("unknown", fallback.sourceService());
        assertNull(SourceMessageCommandFactory.from(withoutOperationId, rawMessageWithSequence("no")).sequenceNumber());
        assertEquals(7L, SourceMessageCommandFactory.from(withoutOperationId, rawMessageWithSequence(7)).sequenceNumber());
    }

    // --- firma ---

    @Test
    void signaturesAreVerifiedOnTheBytesAndATamperedMessageNeverReachesTheProcessor() {
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        MessagePayloadSignatureVerifier required = verifier(SECRET, MessageSignatureMode.REQUIRED);
        assertTrue(required.rejectionReason(body, hmac(SECRET, body), "HMAC-SHA256").isEmpty());
        Optional<String> tampered = verifier(SECRET, MessageSignatureMode.OPTIONAL)
                .rejectionReason(PUBLISHED_MESSAGE_JSON.replace("Sants", "Nord").getBytes(StandardCharsets.UTF_8), hmac(SECRET, body), "HMAC-SHA256");
        assertTrue(tampered.isPresent());
        assertTrue(verifier(SECRET, MessageSignatureMode.OPTIONAL).rejectionReason(body, null, null).isEmpty());
        assertTrue(required.rejectionReason(body, null, null).isPresent());
        assertFalse(new MessageSignatureProperties(SECRET, MessageSignatureMode.REQUIRED).toString().contains(SECRET));

        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        Message raw = rawMessage();
        raw.getMessageProperties().setHeader(SourceMessageHeaders.SIGNATURE, "no es la firma");
        raw.getMessageProperties().setHeader(SourceMessageHeaders.SIGNATURE_ALGORITHM, "HMAC-SHA256");
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new SourceEventConsumer("master-data", processor, required).consume(convert(PUBLISHED_MESSAGE_JSON), raw));
        assertTrue(processor.envelopes.isEmpty());
    }

    // --- topologia ---

    @Test
    void theTopologyDeclaresTheConfigurationExchangeAndOurQueueWithItsDeadLetters() {
        contextRunner.run(context -> {
            Declarables topology = context.getBean("sourceTopology", Declarables.class);
            TopicExchange exchange = topology.getDeclarablesByType(TopicExchange.class).stream()
                    .filter(e -> e.getName().equals(SourceRabbitMqNames.MASTER_DATA_EXCHANGE)).findFirst().orElseThrow();
            assertTrue(exchange.isDurable());
            assertFalse(exchange.isAutoDelete());

            List<Queue> queues = topology.getDeclarablesByType(Queue.class);
            Queue queue = queues.stream().filter(q -> q.getName().equals(SourceRabbitMqNames.MASTER_DATA_QUEUE)).findFirst().orElseThrow();
            assertEquals(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_EXCHANGE, queue.getArguments().get(SourceRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE));
            assertTrue(queues.stream().anyMatch(q -> q.getName().equals(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE)));

            List<Binding> bindings = topology.getDeclarablesByType(Binding.class);
            assertTrue(bindings.stream().anyMatch(b -> b.getExchange().equals(SourceRabbitMqNames.MASTER_DATA_EXCHANGE)
                    && b.getRoutingKey().equals(SourceRabbitMqNames.MASTER_DATA_ROUTING_PATTERN)
                    && b.getDestination().equals(SourceRabbitMqNames.MASTER_DATA_QUEUE)));
            assertEquals(List.of(SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_EXCHANGE,
                            SourceRabbitMqNames.USERS_DEAD_LETTER_EXCHANGE),
                    topology.getDeclarablesByType(DirectExchange.class).stream().map(DirectExchange::getName).sorted().toList());

            // Las otras dos fuentes de RabbitMQ: cada una con su exchange (el del productor), su cola y sus dead letters.
            assertEquals(List.of(SourceRabbitMqNames.CONFIGURATION_EXCHANGE, SourceRabbitMqNames.MASTER_DATA_EXCHANGE, SourceRabbitMqNames.USERS_EXCHANGE),
                    topology.getDeclarablesByType(TopicExchange.class).stream().map(TopicExchange::getName).sorted().toList());
            for (String[] source : new String[][]{
                    {SourceRabbitMqNames.CONFIGURATION_EXCHANGE, SourceRabbitMqNames.CONFIGURATION_ROUTING_PATTERN, SourceRabbitMqNames.CONFIGURATION_QUEUE,
                            SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_QUEUE},
                    {SourceRabbitMqNames.USERS_EXCHANGE, SourceRabbitMqNames.USERS_ROUTING_PATTERN, SourceRabbitMqNames.USERS_QUEUE,
                            SourceRabbitMqNames.USERS_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.USERS_DEAD_LETTER_QUEUE}}) {
                Queue sourceQueue = queues.stream().filter(q -> q.getName().equals(source[2])).findFirst().orElseThrow();
                assertEquals(source[3], sourceQueue.getArguments().get(SourceRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE));
                assertTrue(queues.stream().anyMatch(q -> q.getName().equals(source[4])));
                assertTrue(bindings.stream().anyMatch(b -> b.getExchange().equals(source[0]) && b.getRoutingKey().equals(source[1])
                        && b.getDestination().equals(source[2])), source[2]);
            }
            assertTrue(context.containsBean("configurationSourceConsumer"));
            assertTrue(context.containsBean("usersSourceConsumer"));

            assertEquals(1, context.getBeansOfType(AmqpAdmin.class).size());
            assertTrue(context.containsBean("masterDataSourceConsumer"));

            SimpleRabbitListenerContainerFactory factory = context.getBean(RabbitListenerContainerFactoryNames.SOURCES, SimpleRabbitListenerContainerFactory.class);
            assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(factory, "defaultRequeueRejected"));
        });
    }

    @Test
    void theListenerCanBeDisabledAloneAndTheWholeChannelWithTheMasterSwitch() {
        contextRunner.withPropertyValues("app.rabbitmq.sources.master-data.listener-enabled=false").run(context -> {
            assertFalse(context.containsBean("masterDataSourceConsumer"));
            assertTrue(context.containsBean("configurationSourceConsumer"));
            assertTrue(context.containsBean("usersSourceConsumer"));
            assertTrue(context.containsBean("sourceTopology"));
        });
        contextRunner.withPropertyValues("app.rabbitmq.sources.users.listener-enabled=false",
                "app.rabbitmq.sources.configuration.listener-enabled=false").run(context -> {
            assertTrue(context.containsBean("masterDataSourceConsumer"));
            assertFalse(context.containsBean("configurationSourceConsumer"));
            assertFalse(context.containsBean("usersSourceConsumer"));
        });
        contextRunner.withPropertyValues("app.rabbitmq.enabled=false").run(context -> {
            assertFalse(context.containsBean("masterDataSourceConsumer"));
            assertFalse(context.containsBean("configurationSourceConsumer"));
            assertFalse(context.containsBean("usersSourceConsumer"));
            assertFalse(context.containsBean("sourceTopology"));
            assertFalse(context.containsBean(RabbitListenerContainerFactoryNames.SOURCES));
        });
    }

    @Test
    void aBlankSourceNameRefusesToStart() {
        assertThrows(IllegalArgumentException.class, () -> new SourceRabbitProperties.Source("x", "y", " ", "d", "q", "k", true));
        SourceRabbitProperties properties = new SourceRabbitProperties(true, Map.of("Master-Data",
                new SourceRabbitProperties.Source("e", "k", "q", "dlx", "dlq", "dlk", true)));
        assertNotNull(properties.source("master-data"));
        assertThrows(IllegalStateException.class, () -> properties.source("stock"));
    }

    // --- soporte ---

    static String fixture(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new UncheckedIOException(missing);
        }
    }

    private static SourceEnvelope convert(String json) {
        MessageConverter converter = new RabbitMqConfiguration(new SourceRabbitProperties(true, Map.of()))
                .sourceMessageConverter(JsonMapper.builder().build());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setInferredArgumentType(SourceEnvelope.class);
        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
        return (SourceEnvelope) converter.fromMessage(message);
    }

    private static MessagePayloadSignatureVerifier verifier(String secret, MessageSignatureMode mode) {
        return new MessagePayloadSignatureVerifier(new MessageSignatureProperties(secret, mode));
    }

    private static String hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Message rawMessageWithSequence(Object sequenceNumber) {
        Message raw = rawMessage();
        raw.getMessageProperties().setHeader(SourceMessageHeaders.SEQUENCE_NUMBER, sequenceNumber);
        return raw;
    }

    private static Message rawMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange(SourceRabbitMqNames.MASTER_DATA_EXCHANGE);
        properties.setReceivedRoutingKey("mto.master-data.station.updated");
        properties.setConsumerQueue(SourceRabbitMqNames.MASTER_DATA_QUEUE);
        properties.setMessageId("f4b0a1c2-0000-4000-8000-000000000001");
        properties.setHeader(SourceMessageHeaders.EVENT_TYPE, "MASTER_DATA_STATION_UPDATED");
        properties.setHeader(SourceMessageHeaders.AGGREGATE_TYPE, "station");
        properties.setHeader(SourceMessageHeaders.AGGREGATE_ID, "42");
        properties.setHeader(SourceMessageHeaders.SEQUENCE_NUMBER, 7L);
        properties.setHeader(SourceMessageHeaders.SIGNATURE_ALGORITHM, "SHA-256");
        return MessageBuilder.withBody(PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
    }

    private static final class RecordingProcessor implements SourceEventProcessor {
        private final List<SourceEnvelope> envelopes = new ArrayList<>();
        private final List<InboxMessageCommand> commands = new ArrayList<>();
        private final List<SourceEventContext> contexts = new ArrayList<>();
        private final InboxProcessingResult result;

        private RecordingProcessor(InboxProcessingResult result) {
            this.result = result;
        }

        @Override
        public InboxProcessingResult process(InboxMessageCommand command, SourceEnvelope envelope, SourceEventContext context) {
            commands.add(command);
            envelopes.add(envelope);
            contexts.add(context);
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestProcessorConfiguration {
        @Bean
        SourceEventProcessor sourceEventProcessor() {
            return new RecordingProcessor(InboxProcessingResult.PROCESSED);
        }
    }
}
