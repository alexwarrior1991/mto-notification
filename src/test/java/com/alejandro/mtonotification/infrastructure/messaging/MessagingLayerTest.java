package com.alejandro.mtonotification.infrastructure.messaging;

import com.alejandro.mtonotification.application.dto.deadletter.DeadLetter;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterQueueDepth;
import com.alejandro.mtonotification.application.dto.deadletter.DeadLetterReprocessing;
import com.alejandro.mtonotification.application.dto.deadletter.ReprocessedDeadLetter;
import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.BrokerUnavailableException;
import com.alejandro.mtonotification.application.exception.ConflictException;
import com.alejandro.mtonotification.application.exception.NotFoundException;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.DeadLetterQueues;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import com.alejandro.mtonotification.configuration.messaging.MessageSignatureMode;
import com.alejandro.mtonotification.configuration.messaging.MessageSignatureProperties;
import com.alejandro.mtonotification.configuration.messaging.MessagingConfiguration;
import com.alejandro.mtonotification.configuration.rabbitmq.RabbitMqConfiguration;
import com.alejandro.mtonotification.configuration.rabbitmq.ConsumerQueueProperties;
import com.alejandro.mtonotification.configuration.rabbitmq.SourceRabbitProperties;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.RabbitDeadLetterQueues;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.RabbitListenerContainerFactoryNames;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceEventConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceMessageCommandFactory;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceMessageHeaders;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceRabbitMqNames;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                    "app.rabbitmq.sources.users.dead-letter-routing-key=" + SourceRabbitMqNames.USERS_DEAD_LETTER_ROUTING_KEY,
                    "app.rabbitmq.sources.maintenance.exchange=" + SourceRabbitMqNames.MAINTENANCE_EXCHANGE,
                    "app.rabbitmq.sources.maintenance.routing-key=" + SourceRabbitMqNames.MAINTENANCE_ROUTING_PATTERN,
                    "app.rabbitmq.sources.maintenance.queue=" + SourceRabbitMqNames.MAINTENANCE_QUEUE,
                    "app.rabbitmq.sources.maintenance.dead-letter-exchange=" + SourceRabbitMqNames.MAINTENANCE_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.maintenance.dead-letter-queue=" + SourceRabbitMqNames.MAINTENANCE_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.maintenance.dead-letter-routing-key=" + SourceRabbitMqNames.MAINTENANCE_DEAD_LETTER_ROUTING_KEY,
                    "app.rabbitmq.sources.stock.exchange=" + SourceRabbitMqNames.STOCK_EXCHANGE,
                    "app.rabbitmq.sources.stock.routing-key=" + SourceRabbitMqNames.STOCK_ROUTING_PATTERN,
                    "app.rabbitmq.sources.stock.queue=" + SourceRabbitMqNames.STOCK_QUEUE,
                    "app.rabbitmq.sources.stock.dead-letter-exchange=" + SourceRabbitMqNames.STOCK_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.stock.dead-letter-queue=" + SourceRabbitMqNames.STOCK_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.stock.dead-letter-routing-key=" + SourceRabbitMqNames.STOCK_DEAD_LETTER_ROUTING_KEY,
                    "app.rabbitmq.sources.field.exchange=" + SourceRabbitMqNames.FIELD_EXCHANGE,
                    "app.rabbitmq.sources.field.routing-key=" + SourceRabbitMqNames.FIELD_ROUTING_PATTERN,
                    "app.rabbitmq.sources.field.queue=" + SourceRabbitMqNames.FIELD_QUEUE,
                    "app.rabbitmq.sources.field.dead-letter-exchange=" + SourceRabbitMqNames.FIELD_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.field.dead-letter-queue=" + SourceRabbitMqNames.FIELD_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.field.dead-letter-routing-key=" + SourceRabbitMqNames.FIELD_DEAD_LETTER_ROUTING_KEY,
                    "app.rabbitmq.sources.resources.exchange=" + SourceRabbitMqNames.RESOURCES_EXCHANGE,
                    "app.rabbitmq.sources.resources.routing-key=" + SourceRabbitMqNames.RESOURCES_ROUTING_PATTERN,
                    "app.rabbitmq.sources.resources.queue=" + SourceRabbitMqNames.RESOURCES_QUEUE,
                    "app.rabbitmq.sources.resources.dead-letter-exchange=" + SourceRabbitMqNames.RESOURCES_DEAD_LETTER_EXCHANGE,
                    "app.rabbitmq.sources.resources.dead-letter-queue=" + SourceRabbitMqNames.RESOURCES_DEAD_LETTER_QUEUE,
                    "app.rabbitmq.sources.resources.dead-letter-routing-key=" + SourceRabbitMqNames.RESOURCES_DEAD_LETTER_ROUTING_KEY);

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

        SourceEnvelope accessPoint = convert(fixture("contracts/mto-configuration/master-data-track-access-point-updated.json"));
        assertEquals("track-access-point", accessPoint.data().get("entityName"));
        assertEquals("UPDATED", accessPoint.data().get("operation"));
        assertEquals("MASTER_DATA_TRACK_ACCESS_POINT_UPDATED", accessPoint.eventType());

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

        SourceEnvelope orderAssigned = convert(fixture("contracts/mto-maintenance/order-status-changed.json"));
        assertEquals("mto-maintenance", orderAssigned.origin());
        assertEquals("MAINTENANCE_ORDER_STATUS_CHANGED", orderAssigned.eventType());
        assertEquals("order", orderAssigned.data().get("entityName"));
        assertEquals("status-changed", orderAssigned.data().get("eventName"));
        assertEquals("mantenimiento.responsable", orderAssigned.actor().username());
        assertEquals("8c3b8c1a-1111-4222-8333-444444444444", orderAssigned.correlationId(), "el X-Correlation-Id de la peticion");
        @SuppressWarnings("unchecked") Map<String, Object> orderValues = (Map<String, Object>) orderAssigned.data().get("values");
        assertEquals("ASSIGNED", orderValues.get("to"));
        assertEquals("mantenimiento.tecnico", orderValues.get("assignedUser"));
        assertEquals(12847.99, ((Number) orderValues.get("startKp")).doubleValue(), 0.0001);
        assertNull(orderValues.get("stationId"), "un valor nulo viaja como nulo");

        SourceEnvelope shiftCancelled = convert(fixture("contracts/mto-maintenance/shift-cancelled.json"));
        assertEquals("MAINTENANCE_SHIFT_CANCELLED", shiftCancelled.eventType());
        assertEquals("shift", shiftCancelled.data().get("entityName"));
        assertEquals("cancelled", shiftCancelled.data().get("eventName"));
        assertEquals("mantenimiento.responsable", shiftCancelled.actor().username());
        @SuppressWarnings("unchecked") Map<String, Object> shiftValues = (Map<String, Object>) shiftCancelled.data().get("values");
        assertEquals("CANCELLED", shiftValues.get("status"));
        assertEquals("equipo.jefe", shiftValues.get("teamLeaderUsername"), "la dotacion de mto-resources viaja en el turno");
        assertEquals(List.of("f0000000-0000-4000-8000-000000000002"), shiftValues.get("vehicleIds"));
        assertEquals(3, ((List<?>) shiftValues.get("linemanPersonIds")).size());
        Map<?, ?> shiftVehicle = (Map<?, ?>) ((List<?>) shiftValues.get("vehicles")).getFirst();
        assertEquals("DR-02", shiftVehicle.get("code"));
        assertNull(shiftVehicle.get("parking"), "un vehiculo sin aparcar viaja con su sitio a nulo");

        SourceEnvelope dueSoon = convert(fixture("contracts/mto-maintenance/preventive-due-soon.json"));
        assertEquals("SYSTEM", dueSoon.actor().kind(), "el trabajo diario no tiene persona detras");
        assertNull(dueSoon.correlationId());
        assertEquals("2026-09-29", dueSoon.data().get("entityId"));

        SourceEnvelope belowMinimum = convert(fixture("contracts/mto-stock/material-below-minimum.json"));
        assertEquals("mto-stock", belowMinimum.origin());
        assertEquals("STOCK_MATERIAL_BELOW_MINIMUM", belowMinimum.eventType());
        assertEquals("material", belowMinimum.data().get("entityName"));
        assertEquals("below-minimum", belowMinimum.data().get("eventName"));
        assertEquals("almacen.operario", belowMinimum.actor().username());
        @SuppressWarnings("unchecked") Map<String, Object> stockValues = (Map<String, Object>) belowMinimum.data().get("values");
        assertEquals("GA70", stockValues.get("materialCode"));
        assertEquals(8.0, ((Number) stockValues.get("availableAfter")).doubleValue(), 0.0001);
        assertEquals("OUTPUT", stockValues.get("operation"));

        SourceEnvelope released = convert(fixture("contracts/mto-stock/reservation-released.json"));
        assertEquals("SERVICE", released.actor().kind(), "mto-maintenance libera con su cuenta de servicio");
        assertEquals("service-account-mto-maintenance-svc", released.actor().username());
        @SuppressWarnings("unchecked") Map<String, Object> reservationValues = (Map<String, Object>) released.data().get("values");
        assertEquals("service-account-mto-maintenance-svc", reservationValues.get("createdBy"));

        SourceEnvelope opened = convert(fixture("contracts/mto-field/possession-opened.json"));
        assertEquals("mto-field", opened.origin());
        assertEquals("FIELD_POSSESSION_OPENED", opened.eventType());
        assertEquals("possession", opened.data().get("entityName"));
        assertEquals("opened", opened.data().get("eventName"));
        assertEquals("campo.responsable", opened.actor().username());
        assertEquals("PO-000012", opened.correlationId(), "el codigo de la posesion agrupa la noche entera");
        @SuppressWarnings("unchecked") Map<String, Object> possessionValues = (Map<String, Object>) opened.data().get("values");
        assertEquals("PO-000012", possessionValues.get("code"));
        assertEquals(List.of("EQ-NORTE", "EQ-SUR"), possessionValues.get("teamCodes"));
        assertEquals(2, ((List<?>) possessionValues.get("shifts")).size(), "los turnos de mto-maintenance viajan enteros");

        SourceEnvelope unacknowledged = convert(fixture("contracts/mto-field/possession-evacuation-unacknowledged.json"));
        assertEquals("SYSTEM", unacknowledged.actor().kind(), "el vigilante de acuses no tiene persona detras");
        assertEquals("PO-000012", unacknowledged.correlationId());
        @SuppressWarnings("unchecked") Map<String, Object> overdueValues = (Map<String, Object>) unacknowledged.data().get("values");
        assertEquals(List.of("EQ-SUR"), overdueValues.get("pendingTeams"));
        assertEquals(150, ((Number) overdueValues.get("overdueSeconds")).intValue());

        SourceEnvelope acknowledged = convert(fixture("contracts/mto-field/possession-evacuation-acknowledged.json"));
        assertEquals("campo.tecnico1", acknowledged.actor().username(), "quien acusa es el tecnico del dispositivo");
        @SuppressWarnings("unchecked") Map<String, Object> ackValues = (Map<String, Object>) acknowledged.data().get("values");
        assertEquals(Boolean.TRUE, ackValues.get("accepted"));
        assertNull(ackValues.get("reason"), "un valor nulo viaja como nulo");

        SourceEnvelope teamUpdated = convert(fixture("contracts/mto-resources/team-updated.json"));
        assertEquals("mto-resources", teamUpdated.origin());
        assertEquals("RESOURCES_TEAM_UPDATED", teamUpdated.eventType());
        assertEquals("team", teamUpdated.data().get("entityName"));
        assertEquals("updated", teamUpdated.data().get("eventName"));
        @SuppressWarnings("unchecked") Map<String, Object> teamValues = (Map<String, Object>) teamUpdated.data().get("values");
        @SuppressWarnings("unchecked") Map<String, Object> leader = (Map<String, Object>) teamValues.get("leader");
        assertNotNull(leader.get("username"), "el jefe del equipo viaja con su usuario: a quien avisar");
        assertEquals(Boolean.FALSE, teamValues.get("republished"));

        SourceEnvelope vehicleUpdated = convert(fixture("contracts/mto-resources/vehicle-updated.json"));
        @SuppressWarnings("unchecked") Map<String, Object> vehicleValues = (Map<String, Object>) vehicleUpdated.data().get("values");
        assertEquals(Boolean.FALSE, vehicleValues.get("available"));
        assertEquals(Boolean.TRUE, vehicleValues.get("availabilityChanged"), "un vehiculo que pasa a reparacion");

        SourceEnvelope qualificationExpiring = convert(fixture("contracts/mto-resources/qualification-expiring.json"));
        assertEquals("RESOURCES_QUALIFICATION_EXPIRING", qualificationExpiring.eventType());
        assertEquals("SYSTEM", qualificationExpiring.actor().kind(), "el vigilante de caducidades no tiene persona detras");
        assertNull(qualificationExpiring.actor().username());
        assertNull(qualificationExpiring.correlationId());
        @SuppressWarnings("unchecked") Map<String, Object> qualificationValues = (Map<String, Object>) qualificationExpiring.data().get("values");
        @SuppressWarnings("unchecked") Map<String, Object> qualificationLeader = (Map<String, Object>) qualificationValues.get("leader");
        assertEquals("equipo.jefe", qualificationLeader.get("username"), "el jefe del equipo de la persona, a quien avisar");
        SourceEnvelope documentExpired = convert(fixture("contracts/mto-resources/vehicle-document-expired.json"));
        assertEquals("vehicle-document", documentExpired.data().get("entityName"), "la entidad con su guion, como la clave de enrutado");
        assertEquals("RESOURCES_VEHICLE_DOCUMENT_EXPIRED", documentExpired.eventType());
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

    // --- las DLQ ---

    @Test
    void theDeadLetterQueuesAreCountedPerSourceAndAMissingOneSaysSo() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueInfo(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE))
                .thenReturn(new QueueInformation(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE, 3, 0));
        RabbitDeadLetterQueues deadLetters = deadLetters(mock(Channel.class), admin, new RecordingProcessor(InboxProcessingResult.PROCESSED), NO_SIGNATURE_CHECK);

        assertEquals(List.of(
                        new DeadLetterQueueDepth("master-data", SourceRabbitMqNames.MASTER_DATA_QUEUE, SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE, 3L),
                        new DeadLetterQueueDepth("users", SourceRabbitMqNames.USERS_QUEUE, SourceRabbitMqNames.USERS_DEAD_LETTER_QUEUE, null)),
                deadLetters.depths(), "por orden alfabetico; la que el broker no tiene, sin recuento");
        assertEquals(3L, deadLetters.depth(" Master-Data ").messages());

        NotFoundException unknown = assertThrows(NotFoundException.class, () -> deadLetters.depth("warehouse"));
        assertEquals("Source", unknown.getAggregate());
        assertThrows(NotFoundException.class, () -> deadLetters.peek("warehouse", 5));
        assertThrows(ConflictException.class, () -> deadLetters.peek("users", 5), "una DLQ que no existe no se lee");
        assertThrows(ConflictException.class, () -> deadLetters.reprocess("users", 5, Duration.ofSeconds(10)));
    }

    @Test
    void peekingLeavesEveryDeadLetterInItsQueueAndSaysWhyItWouldNotReachTheInbox() throws IOException {
        Channel channel = openChannel();
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        when(channel.basicGet(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE, false)).thenReturn(
                deadLetter(11, body, hmac(SECRET, body)),
                deadLetter(12, PUBLISHED_MESSAGE_JSON.replace("Sants", "Nord").getBytes(StandardCharsets.UTF_8), hmac(SECRET, body)),
                deadLetter(13, "no es json".getBytes(StandardCharsets.UTF_8), null),
                null);
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        RabbitDeadLetterQueues deadLetters = deadLetters(channel, existingQueues(), processor, verifier(SECRET, MessageSignatureMode.OPTIONAL));

        List<DeadLetter> peeked = deadLetters.peek("master-data", 10);

        assertEquals(3, peeked.size());
        DeadLetter first = peeked.getFirst();
        assertEquals("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", first.messageId(), "la clave del inbox: el operationId del sobre");
        assertEquals("mto-configuration", first.sourceService());
        assertEquals("MASTER_DATA_STATION_UPDATED", first.eventType());
        assertEquals("station", first.aggregateType());
        assertEquals(SourceRabbitMqNames.MASTER_DATA_EXCHANGE, first.exchange(), "lo original, de x-death, no el DLX");
        assertEquals("mto.master-data.station.updated", first.routingKey());
        assertEquals("rejected", first.deathReason());
        assertEquals(1L, first.deathCount());
        assertEquals(Instant.parse("2026-09-01T10:16:00Z"), first.deadLetteredAt());
        assertEquals(body.length, first.payloadBytes());
        assertNull(first.problem(), "este llegaria al inbox");
        assertTrue(peeked.get(1).problem().contains("signature does not match"), peeked.get(1).problem());
        assertNotNull(peeked.get(2).problem(), "un cuerpo que no es el sobre");
        assertEquals("amqp-13", peeked.get(2).messageId(), "sin sobre, el message_id de AMQP");

        assertTrue(processor.envelopes.isEmpty(), "mirar no procesa nada");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(13, true, true);
        verify(channel, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());

        Channel bounded = openChannel();
        when(bounded.basicGet(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE, false)).thenReturn(deadLetter(1, body, null), deadLetter(2, body, null));
        assertEquals(1, deadLetters(bounded, existingQueues(), processor, NO_SIGNATURE_CHECK).peek("master-data", 1).size());
        verify(bounded, times(1)).basicGet(anyString(), anyBoolean());
        verify(bounded).basicNack(1, true, true);
    }

    @Test
    void reprocessingAcknowledgesWhatWasAppliedAndReturnsOnceWhatFailsAgain() throws IOException {
        Channel channel = openChannel();
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        String poison = PUBLISHED_MESSAGE_JSON.replace("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b66");
        String repeated = PUBLISHED_MESSAGE_JSON.replace("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b77");
        when(channel.basicGet(SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE, false)).thenReturn(
                deadLetter(1, body, null),
                deadLetter(2, poison.getBytes(StandardCharsets.UTF_8), null),
                deadLetter(3, repeated.getBytes(StandardCharsets.UTF_8), null),
                null);
        List<InboxMessageCommand> commands = new ArrayList<>();
        List<SourceEventContext> contexts = new ArrayList<>();
        SourceEventProcessor processor = (command, envelope, context) -> {
            commands.add(command);
            contexts.add(context);
            if (command.messageId().endsWith("66")) {
                throw new IllegalStateException("the adapter still cannot apply it");
            }
            return command.messageId().endsWith("77") ? InboxProcessingResult.DUPLICATE_SKIPPED : InboxProcessingResult.PROCESSED;
        };

        DeadLetterReprocessing reprocessing = deadLetters(channel, existingQueues(), processor, NO_SIGNATURE_CHECK)
                .reprocess("master-data", 10, Duration.ofSeconds(10));

        assertEquals(List.of(ReprocessedDeadLetter.Outcome.PROCESSED, ReprocessedDeadLetter.Outcome.FAILED, ReprocessedDeadLetter.Outcome.DUPLICATE),
                reprocessing.results().stream().map(ReprocessedDeadLetter::outcome).toList());
        assertEquals(DeadLetterReprocessing.StoppedBy.EMPTY, reprocessing.stoppedBy());
        assertEquals("the adapter still cannot apply it", reprocessing.results().get(1).reason());
        assertEquals("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b66", reprocessing.results().get(1).messageId());
        assertEquals(3, commands.size(), "cada mensaje pasa una vez: el que falla no se vuelve a sacar en la misma pasada");
        assertEquals(SourceRabbitMqNames.MASTER_DATA_EXCHANGE, commands.getFirst().exchangeName(), "el inbox guarda lo original, no el DLX");
        assertEquals("mto.master-data.station.updated", commands.getFirst().routingKey());
        assertEquals(SourceRabbitMqNames.MASTER_DATA_QUEUE, commands.getFirst().queueName());
        assertEquals("master-data", contexts.getFirst().sourceId());
        assertEquals(7L, contexts.getFirst().sequenceNumber(), "las cabeceras del emisor sobreviven al descarte");

        // Los aplicados se confirman uno a uno; el fallido vuelve a la cola una vez y al final.
        InOrder order = inOrder(channel);
        order.verify(channel).basicAck(1, false);
        order.verify(channel).basicAck(3, false);
        order.verify(channel).basicNack(2, true, true);
        verify(channel, times(2)).basicAck(anyLong(), anyBoolean());
        verify(channel, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, times(4)).basicGet(anyString(), anyBoolean());
    }

    @Test
    void reprocessingChecksTheSignatureAgainAndStopsAtItsMaximumAndItsTimeBudget() throws IOException {
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);

        // El que murio por su firma vuelve a morir por ella: no entra por la puerta de atras.
        Channel tampered = openChannel();
        when(tampered.basicGet(anyString(), anyBoolean())).thenReturn(
                deadLetter(1, PUBLISHED_MESSAGE_JSON.replace("Sants", "Nord").getBytes(StandardCharsets.UTF_8), hmac(SECRET, body)),
                deadLetter(2, "no es json".getBytes(StandardCharsets.UTF_8), null),
                null);
        DeadLetterReprocessing rejected = deadLetters(tampered, existingQueues(), processor, verifier(SECRET, MessageSignatureMode.REQUIRED))
                .reprocess("master-data", 10, Duration.ofSeconds(10));
        assertEquals(2, rejected.count(ReprocessedDeadLetter.Outcome.FAILED));
        assertTrue(rejected.results().getFirst().reason().contains("signature does not match"), rejected.results().getFirst().reason());
        assertTrue(processor.envelopes.isEmpty());
        verify(tampered, never()).basicAck(anyLong(), anyBoolean());
        verify(tampered).basicNack(2, true, true);

        // Con todo aplicado no queda nada que devolver: un nack de una entrega ya confirmada cerraria el canal.
        Channel applied = openChannel();
        when(applied.basicGet(anyString(), anyBoolean())).thenReturn(deadLetter(1, body, null), deadLetter(2, body, null), deadLetter(3, body, null), null);
        DeadLetterReprocessing bounded = deadLetters(applied, existingQueues(), processor, NO_SIGNATURE_CHECK)
                .reprocess("master-data", 2, Duration.ofSeconds(10));
        assertEquals(DeadLetterReprocessing.StoppedBy.MAX, bounded.stoppedBy());
        assertEquals(2, bounded.count(ReprocessedDeadLetter.Outcome.PROCESSED));
        verify(applied, times(2)).basicGet(anyString(), anyBoolean());
        verify(applied, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());

        Channel late = openChannel();
        DeadLetterReprocessing outOfTime = deadLetters(late, existingQueues(), processor, NO_SIGNATURE_CHECK)
                .reprocess("master-data", 5, Duration.ZERO);
        assertEquals(DeadLetterReprocessing.StoppedBy.TIME_BUDGET, outOfTime.stoppedBy());
        assertTrue(outOfTime.results().isEmpty());
        verify(late, never()).basicGet(anyString(), anyBoolean());
    }

    @Test
    void aBrokerThatDoesNotAnswerIsAnOutageAndWhatWasTakenOutGoesBack() throws IOException {
        AmqpAdmin down = mock(AmqpAdmin.class);
        when(down.getQueueInfo(anyString())).thenThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")));
        RabbitDeadLetterQueues unreachable = deadLetters(mock(Channel.class), down, new RecordingProcessor(InboxProcessingResult.PROCESSED), NO_SIGNATURE_CHECK);
        assertThrows(BrokerUnavailableException.class, unreachable::depths);
        assertThrows(BrokerUnavailableException.class, () -> unreachable.peek("master-data", 1));

        // El canal se rompe a mitad: lo ya sacado se devuelve (o lo devuelve el broker al cerrar el canal).
        Channel channel = openChannel();
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        when(channel.basicGet(anyString(), anyBoolean())).thenReturn(deadLetter(1, body, null)).thenThrow(new IOException("connection reset"));
        RabbitDeadLetterQueues broken = deadLetters(channel, existingQueues(), new RecordingProcessor(InboxProcessingResult.PROCESSED), NO_SIGNATURE_CHECK);
        assertThrows(BrokerUnavailableException.class, () -> broken.peek("master-data", 5));
        verify(channel).basicNack(1, true, true);
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
            assertEquals(List.of(SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.FIELD_DEAD_LETTER_EXCHANGE,
                            SourceRabbitMqNames.MAINTENANCE_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_EXCHANGE,
                            SourceRabbitMqNames.RESOURCES_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.STOCK_DEAD_LETTER_EXCHANGE,
                            SourceRabbitMqNames.USERS_DEAD_LETTER_EXCHANGE),
                    topology.getDeclarablesByType(DirectExchange.class).stream().map(DirectExchange::getName).sorted().toList());

            // Las siete fuentes de RabbitMQ: cada una con su exchange (el del productor), su cola y sus dead letters.
            assertEquals(List.of(SourceRabbitMqNames.CONFIGURATION_EXCHANGE, SourceRabbitMqNames.FIELD_EXCHANGE, SourceRabbitMqNames.MAINTENANCE_EXCHANGE,
                            SourceRabbitMqNames.MASTER_DATA_EXCHANGE, SourceRabbitMqNames.RESOURCES_EXCHANGE, SourceRabbitMqNames.STOCK_EXCHANGE,
                            SourceRabbitMqNames.USERS_EXCHANGE),
                    topology.getDeclarablesByType(TopicExchange.class).stream().map(TopicExchange::getName).sorted().toList());
            for (String[] source : new String[][]{
                    {SourceRabbitMqNames.CONFIGURATION_EXCHANGE, SourceRabbitMqNames.CONFIGURATION_ROUTING_PATTERN, SourceRabbitMqNames.CONFIGURATION_QUEUE,
                            SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.CONFIGURATION_DEAD_LETTER_QUEUE},
                    {SourceRabbitMqNames.USERS_EXCHANGE, SourceRabbitMqNames.USERS_ROUTING_PATTERN, SourceRabbitMqNames.USERS_QUEUE,
                            SourceRabbitMqNames.USERS_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.USERS_DEAD_LETTER_QUEUE},
                    {SourceRabbitMqNames.MAINTENANCE_EXCHANGE, SourceRabbitMqNames.MAINTENANCE_ROUTING_PATTERN, SourceRabbitMqNames.MAINTENANCE_QUEUE,
                            SourceRabbitMqNames.MAINTENANCE_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.MAINTENANCE_DEAD_LETTER_QUEUE},
                    {SourceRabbitMqNames.STOCK_EXCHANGE, SourceRabbitMqNames.STOCK_ROUTING_PATTERN, SourceRabbitMqNames.STOCK_QUEUE,
                            SourceRabbitMqNames.STOCK_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.STOCK_DEAD_LETTER_QUEUE},
                    {SourceRabbitMqNames.FIELD_EXCHANGE, SourceRabbitMqNames.FIELD_ROUTING_PATTERN, SourceRabbitMqNames.FIELD_QUEUE,
                            SourceRabbitMqNames.FIELD_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.FIELD_DEAD_LETTER_QUEUE},
                    {SourceRabbitMqNames.RESOURCES_EXCHANGE, SourceRabbitMqNames.RESOURCES_ROUTING_PATTERN, SourceRabbitMqNames.RESOURCES_QUEUE,
                            SourceRabbitMqNames.RESOURCES_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.RESOURCES_DEAD_LETTER_QUEUE}}) {
                Queue sourceQueue = queues.stream().filter(q -> q.getName().equals(source[2])).findFirst().orElseThrow();
                assertEquals(source[3], sourceQueue.getArguments().get(SourceRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE));
                assertTrue(queues.stream().anyMatch(q -> q.getName().equals(source[4])));
                assertTrue(bindings.stream().anyMatch(b -> b.getExchange().equals(source[0]) && b.getRoutingKey().equals(source[1])
                        && b.getDestination().equals(source[2])), source[2]);
            }
            assertTrue(context.containsBean("configurationSourceConsumer"));
            assertTrue(context.containsBean("usersSourceConsumer"));
            assertTrue(context.containsBean("maintenanceSourceConsumer"));
            assertTrue(context.containsBean("stockSourceConsumer"));
            assertTrue(context.containsBean("fieldSourceConsumer"));
            assertTrue(context.containsBean("resourcesSourceConsumer"));

            assertEquals(1, context.getBeansOfType(AmqpAdmin.class).size());
            assertTrue(context.containsBean("masterDataSourceConsumer"));

            SimpleRabbitListenerContainerFactory factory = context.getBean(RabbitListenerContainerFactoryNames.SOURCES, SimpleRabbitListenerContainerFactory.class);
            assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(factory, "defaultRequeueRejected"));
        });
    }

    /**
     * Sin decir nada, las siete colas y sus DLQ se declaran exactamente como antes: clasicas, sin
     * {@code x-queue-type} ni {@code x-delivery-limit}. Un argumento de mas haria que el broker
     * rechazase la redeclaracion de una cola ya creada y se cayese toda la topologia.
     */
    @Test
    void byDefaultEverySourceQueueAndItsDeadLetterAreClassicAndDeclaredAsBefore() {
        contextRunner.run(context -> {
            List<Queue> queues = context.getBean("sourceTopology", Declarables.class).getDeclarablesByType(Queue.class);
            assertEquals(14, queues.size());
            for (Queue queue : queues) {
                if (queue.getName().endsWith(".dlq")) {
                    assertEquals(Map.of(), queue.getArguments(), queue.getName());
                } else {
                    assertEquals(Set.of(SourceRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE, SourceRabbitMqNames.ARG_DEAD_LETTER_ROUTING_KEY),
                            queue.getArguments().keySet(), queue.getName());
                }
            }
        });
    }

    /**
     * Con {@code app.rabbitmq.queues.type=quorum}, que es como corre en OpenShift, las siete colas son
     * quorum con su limite de entregas (5, o el que se diga) y sus DLQ tambien, sin limite.
     */
    @Test
    void withQuorumEverySourceQueueCarriesItsDeliveryLimitAndItsDeadLetterIsQuorumToo() {
        contextRunner.withPropertyValues("app.rabbitmq.queues.type=quorum").run(context -> {
            List<Queue> queues = context.getBean("sourceTopology", Declarables.class).getDeclarablesByType(Queue.class);
            assertEquals(14, queues.size());
            for (Queue queue : queues) {
                if (queue.getName().endsWith(".dlq")) {
                    assertEquals(Map.of("x-queue-type", "quorum"), queue.getArguments(), queue.getName());
                } else {
                    assertEquals("quorum", queue.getArguments().get("x-queue-type"), queue.getName());
                    assertEquals(ConsumerQueueProperties.DEFAULT_DELIVERY_LIMIT, queue.getArguments().get("x-delivery-limit"), queue.getName());
                    assertNotNull(queue.getArguments().get(SourceRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE), queue.getName());
                }
            }
        });
        contextRunner.withPropertyValues("app.rabbitmq.queues.type=QUORUM", "app.rabbitmq.queues.delivery-limit=3").run(context ->
                assertTrue(context.getBean("sourceTopology", Declarables.class).getDeclarablesByType(Queue.class).stream()
                        .filter(queue -> !queue.getName().endsWith(".dlq"))
                        .allMatch(queue -> Integer.valueOf(3).equals(queue.getArguments().get("x-delivery-limit")))));
    }

    @Test
    void aDeliveryLimitBelowOneDoesNotStart() {
        contextRunner.withPropertyValues("app.rabbitmq.queues.type=quorum", "app.rabbitmq.queues.delivery-limit=0")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void theListenerCanBeDisabledAloneAndTheWholeChannelWithTheMasterSwitch() {
        contextRunner.withPropertyValues("app.rabbitmq.sources.master-data.listener-enabled=false").run(context -> {
            assertFalse(context.containsBean("masterDataSourceConsumer"));
            assertTrue(context.containsBean("configurationSourceConsumer"));
            assertTrue(context.containsBean("usersSourceConsumer"));
            assertTrue(context.containsBean("fieldSourceConsumer"));
            assertTrue(context.containsBean("resourcesSourceConsumer"));
            assertTrue(context.containsBean("sourceTopology"));
            assertNotNull(context.getBean(DeadLetterQueues.class), "las DLQ se administran aunque una escucha este apagada");
        });
        contextRunner.withPropertyValues("app.rabbitmq.sources.users.listener-enabled=false",
                "app.rabbitmq.sources.configuration.listener-enabled=false",
                "app.rabbitmq.sources.maintenance.listener-enabled=false",
                "app.rabbitmq.sources.stock.listener-enabled=false",
                "app.rabbitmq.sources.field.listener-enabled=false").run(context -> {
            assertTrue(context.containsBean("masterDataSourceConsumer"));
            assertFalse(context.containsBean("configurationSourceConsumer"));
            assertFalse(context.containsBean("usersSourceConsumer"));
            assertFalse(context.containsBean("maintenanceSourceConsumer"));
            assertFalse(context.containsBean("stockSourceConsumer"));
            assertFalse(context.containsBean("fieldSourceConsumer"));
        });
        contextRunner.withPropertyValues("app.rabbitmq.enabled=false").run(context -> {
            assertFalse(context.containsBean("masterDataSourceConsumer"));
            assertFalse(context.containsBean("configurationSourceConsumer"));
            assertFalse(context.containsBean("usersSourceConsumer"));
            assertFalse(context.containsBean("maintenanceSourceConsumer"));
            assertFalse(context.containsBean("stockSourceConsumer"));
            assertFalse(context.containsBean("fieldSourceConsumer"));
            assertFalse(context.containsBean("sourceTopology"));
            assertFalse(context.containsBean(RabbitListenerContainerFactoryNames.SOURCES));
            assertEquals(0, context.getBeanNamesForType(DeadLetterQueues.class).length, "sin broker no hay DLQ que administrar");
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
        MessageConverter converter = new RabbitMqConfiguration(new SourceRabbitProperties(true, Map.of()), new ConsumerQueueProperties(null, null))
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

    private static final SourceRabbitProperties DEAD_LETTER_SOURCES = new SourceRabbitProperties(true, Map.of(
            "master-data", new SourceRabbitProperties.Source(SourceRabbitMqNames.MASTER_DATA_EXCHANGE, SourceRabbitMqNames.MASTER_DATA_ROUTING_PATTERN,
                    SourceRabbitMqNames.MASTER_DATA_QUEUE, SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_EXCHANGE,
                    SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_QUEUE, SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_ROUTING_KEY, true),
            "users", new SourceRabbitProperties.Source(SourceRabbitMqNames.USERS_EXCHANGE, SourceRabbitMqNames.USERS_ROUTING_PATTERN,
                    SourceRabbitMqNames.USERS_QUEUE, SourceRabbitMqNames.USERS_DEAD_LETTER_EXCHANGE,
                    SourceRabbitMqNames.USERS_DEAD_LETTER_QUEUE, SourceRabbitMqNames.USERS_DEAD_LETTER_ROUTING_KEY, true)));

    /** Las DLQ sobre un canal simulado: lo que se comprueba es que se le pide al broker, y en que orden. */
    private static RabbitDeadLetterQueues deadLetters(Channel channel, AmqpAdmin admin, SourceEventProcessor processor,
                                                      MessagePayloadSignatureVerifier signatureVerifier) {
        Connection connection = mock(Connection.class);
        when(connection.createChannel(false)).thenReturn(channel);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.createConnection()).thenReturn(connection);
        MessageConverter converter = new RabbitMqConfiguration(DEAD_LETTER_SOURCES, new ConsumerQueueProperties(null, null))
                .sourceMessageConverter(JsonMapper.builder().build());
        return new RabbitDeadLetterQueues(connectionFactory, admin, DEAD_LETTER_SOURCES, converter, processor, signatureVerifier);
    }

    private static Channel openChannel() {
        Channel channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        return channel;
    }

    private static AmqpAdmin existingQueues() {
        AmqpAdmin admin = mock(AmqpAdmin.class);
        when(admin.getQueueInfo(anyString())).thenAnswer(call -> new QueueInformation(call.getArgument(0), 1, 0));
        return admin;
    }

    /** Un mensaje tal como sale de la DLQ: por el DLX, con la clave de la DLQ y con lo original en {@code x-death}. */
    private static GetResponse deadLetter(long deliveryTag, byte[] body, String signature) {
        Map<String, Object> headers = new java.util.HashMap<>();
        headers.put(SourceMessageHeaders.EVENT_TYPE, "MASTER_DATA_STATION_UPDATED");
        headers.put(SourceMessageHeaders.SEQUENCE_NUMBER, 7L);
        if (signature != null) {
            headers.put(SourceMessageHeaders.SIGNATURE, signature);
            headers.put(SourceMessageHeaders.SIGNATURE_ALGORITHM, "HMAC-SHA256");
        }
        headers.put("x-death", List.of(Map.of(
                "count", 1L,
                "reason", "rejected",
                "queue", SourceRabbitMqNames.MASTER_DATA_QUEUE,
                "time", java.util.Date.from(Instant.parse("2026-09-01T10:16:00Z")),
                "exchange", SourceRabbitMqNames.MASTER_DATA_EXCHANGE,
                "routing-keys", List.of("mto.master-data.station.updated"))));
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(MessageProperties.CONTENT_TYPE_JSON)
                .contentEncoding(StandardCharsets.UTF_8.name())
                .messageId("amqp-" + deliveryTag)
                .headers(headers)
                .build();
        Envelope envelope = new Envelope(deliveryTag, false, SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_EXCHANGE,
                SourceRabbitMqNames.MASTER_DATA_DEAD_LETTER_ROUTING_KEY);
        return new GetResponse(envelope, properties, body, 0);
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
