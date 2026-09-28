package com.alejandro.mtonotification.infrastructure.persistence.repository;

import com.alejandro.mtonotification.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtonotification.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessage;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El inbox contra PostgreSQL: la idempotencia es la {@code unique(message_id, source_service)} y el
 * idioma del recuento de filas, no una comprobacion en codigo. El mismo inbox sirve a RabbitMQ y al
 * lector de Keycloak, asi que se prueba con una fuente de cada.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class InboxMessageRepositoryDataJpaTest extends PostgreSQLTestContainer {

    /** El servicio del inbox es package-private tras su interfaz: se recoge por nombre. */
    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.alejandro.mtonotification.application.service.impl", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.InboxMessageServiceImpl"))
    static class InboxServiceConfiguration {
    }

    private static final String MESSAGE_ID = "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10";
    private static final String SOURCE_SERVICE = "mto-configuration";
    private static final String PAYLOAD = """
            {"operationId":"0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10","eventType":"MASTER_DATA_STATION_UPDATED"}""";

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InboxMessageRepository inboxMessageRepository;

    @Autowired
    private InboxMessageService inboxMessageService;

    @Test
    void firstDeliveryIsRecordedClaimedAndMarkedAsProcessedWithOneAttempt() {
        assertEquals(1, insert(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(1, inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(1, inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE));

        InboxMessage stored = reload(MESSAGE_ID, SOURCE_SERVICE);
        assertEquals(InboxMessageStatus.PROCESSED, stored.getStatus());
        assertEquals(1, stored.getProcessingAttempts());
        assertNotNull(stored.getProcessedAt());
        assertEquals(PAYLOAD, stored.getPayload(), "json, no jsonb: los bytes se guardan tal cual");
        assertTrue(stored.isProcessed());
    }

    @Test
    void redeliveryOfAnAppliedMessageNeitherInsertsNorClaims() {
        insert(MESSAGE_ID, SOURCE_SERVICE);
        inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE);
        inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE);

        assertEquals(0, insert(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(0, inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(1, reload(MESSAGE_ID, SOURCE_SERVICE).getProcessingAttempts());
    }

    @Test
    void theSameIdFromAnotherSourceIsAnotherMessage() {
        assertEquals(1, insert(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(1, insert(MESSAGE_ID, "keycloak-login"));
        assertEquals(1, inboxMessageRepository.claimForProcessing(MESSAGE_ID, "keycloak-login"));
    }

    @Test
    void aFailureIsRecordedWithItsReasonAndAFailedMessageCanBeClaimedAgain() {
        insert(MESSAGE_ID, SOURCE_SERVICE);
        inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE);
        assertEquals(1, inboxMessageRepository.recordFailure(MESSAGE_ID, SOURCE_SERVICE, "MASTER_DATA_STATION_UPDATED", "station", "42",
                "mto.master-data.exchange", "mto.master-data.station.updated", "mto.notification.master-data.queue", "hash", PAYLOAD,
                "java.lang.IllegalStateException: database is down"));

        InboxMessage failed = reload(MESSAGE_ID, SOURCE_SERVICE);
        assertEquals(InboxMessageStatus.FAILED, failed.getStatus());
        assertEquals(2, failed.getProcessingAttempts(), "el intento reclamado mas el fallo registrado");
        assertNotNull(failed.getFailedAt());
        assertEquals("java.lang.IllegalStateException: database is down", failed.getFailureReason());

        assertEquals(1, inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE));
        InboxMessage retried = reload(MESSAGE_ID, SOURCE_SERVICE);
        assertEquals(InboxMessageStatus.PROCESSING, retried.getStatus());
        assertNull(retried.getFailedAt());
        assertNull(retried.getFailureReason());
    }

    @Test
    void aFailureOfAMessageNeverRecordedInsertsItAsFailed() {
        assertEquals(1, inboxMessageRepository.recordFailure("nunca-visto", "keycloak-login", "LOGIN", "user", "u1",
                null, null, null, "hash", "{}", "boom"));
        InboxMessage failed = reload("nunca-visto", "keycloak-login");
        assertEquals(InboxMessageStatus.FAILED, failed.getStatus());
        assertEquals(1, failed.getProcessingAttempts());
    }

    @Test
    void theServiceRunsTheHandlerOnceAndSkipsItOnADuplicate() {
        AtomicInteger runs = new AtomicInteger();
        InboxMessageCommand command = new InboxMessageCommand(MESSAGE_ID, SOURCE_SERVICE, "MASTER_DATA_STATION_UPDATED", "station", "42",
                "mto.master-data.exchange", "mto.master-data.station.updated", "mto.notification.master-data.queue", "hash", PAYLOAD, 7L);

        assertEquals(InboxProcessingResult.PROCESSED, inboxMessageService.process(command, runs::incrementAndGet));
        assertEquals(InboxProcessingResult.DUPLICATE_SKIPPED, inboxMessageService.process(command, runs::incrementAndGet));

        assertEquals(1, runs.get());
        assertEquals(InboxMessageStatus.PROCESSED, reload(MESSAGE_ID, SOURCE_SERVICE).getStatus());
        assertThrows(RuntimeException.class, () -> inboxMessageService.process(
                new InboxMessageCommand(" ", SOURCE_SERVICE, null, null, null, null, null, null, "h", "{}", null), runs::incrementAndGet));
    }

    @Test
    void countsPerSourceAndStatusFeedTheAdminView() {
        insert(MESSAGE_ID, SOURCE_SERVICE);
        insert("otro", SOURCE_SERVICE);
        inboxMessageRepository.claimForProcessing("otro", SOURCE_SERVICE);
        inboxMessageRepository.markProcessed("otro", SOURCE_SERVICE);
        insert("kc", "keycloak-login");

        List<InboxMessageRepository.SourceStatusCount> counts = inboxMessageRepository.countBySourceAndStatus();
        assertEquals(3, counts.size());
        assertEquals("keycloak-login", counts.getFirst().getSourceService(), "por fuente y estado");
        Map<String, Long> totals = counts.stream().collect(Collectors.toMap(
                count -> count.getSourceService() + "/" + count.getStatus(), InboxMessageRepository.SourceStatusCount::getTotal));
        assertEquals(Map.of("keycloak-login/RECEIVED", 1L, "mto-configuration/RECEIVED", 1L, "mto-configuration/PROCESSED", 1L), totals);
    }

    @Test
    void thePurgeOnlyDeletesProcessedMessagesOlderThanTheDate() {
        insert(MESSAGE_ID, SOURCE_SERVICE);
        inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE);
        inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE);
        insert("pendiente", SOURCE_SERVICE);
        entityManager.createNativeQuery("update inbox_message set received_at = now() - interval '10 days'").executeUpdate();

        assertEquals(1, inboxMessageRepository.deleteProcessedBefore(Instant.now().minusSeconds(3600), 100));
        assertEquals(0, inboxMessageRepository.deleteProcessedBefore(Instant.now().minusSeconds(3600), 100));
        entityManager.clear();
        assertTrue(inboxMessageRepository.findByMessageIdAndSourceService("pendiente", SOURCE_SERVICE).isPresent());
        assertTrue(inboxMessageRepository.findByMessageIdAndSourceService(MESSAGE_ID, SOURCE_SERVICE).isEmpty());
    }

    private int insert(String messageId, String sourceService) {
        return inboxMessageRepository.insertIfMissing(messageId, sourceService, "MASTER_DATA_STATION_UPDATED", "station", "42",
                "mto.master-data.exchange", "mto.master-data.station.updated", "mto.notification.master-data.queue", "hash", PAYLOAD);
    }

    private InboxMessage reload(String messageId, String sourceService) {
        entityManager.clear();
        return inboxMessageRepository.findByMessageIdAndSourceService(messageId, sourceService).orElseThrow();
    }
}
