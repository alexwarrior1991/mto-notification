package com.alejandro.mtonotification;

import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.ActivityQueryService;
import com.alejandro.mtonotification.application.service.ActivitySourceAdapter;
import com.alejandro.mtonotification.application.service.AdminService;
import com.alejandro.mtonotification.application.service.AudienceResolver;
import com.alejandro.mtonotification.application.service.BurstAggregator;
import com.alejandro.mtonotification.application.service.DeliveryChannel;
import com.alejandro.mtonotification.application.service.DeliveryDispatchTrigger;
import com.alejandro.mtonotification.application.service.DeliveryDispatcher;
import com.alejandro.mtonotification.application.service.DeliveryRelayService;
import com.alejandro.mtonotification.application.service.DerivedEventDetector;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.application.service.InboxQueryService;
import com.alejandro.mtonotification.application.service.KeycloakDirectoryClient;
import com.alejandro.mtonotification.application.service.KeycloakEventsClient;
import com.alejandro.mtonotification.application.service.KeycloakEventsPoller;
import com.alejandro.mtonotification.application.service.NotificationFactory;
import com.alejandro.mtonotification.application.service.RetentionPurge;
import com.alejandro.mtonotification.application.service.RuleEngine;
import com.alejandro.mtonotification.application.service.RuleRepository;
import com.alejandro.mtonotification.application.service.SourceCursorService;
import com.alejandro.mtonotification.application.service.SourceEventHandler;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.application.service.ThrottleGate;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakPollingConfiguration;
import com.alejandro.mtonotification.configuration.web.CorrelationIdFilter;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.MasterDataSourceConsumer;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.web.controller.AccessController;
import com.alejandro.mtonotification.infrastructure.web.controller.ActivityController;
import com.alejandro.mtonotification.infrastructure.web.controller.AdminController;
import com.alejandro.mtonotification.infrastructure.web.controller.InboxController;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El {@code @SpringBootTest} de la suite: arranca el contexto entero contra un PostgreSQL real
 * (Flyway aplica V1 y Hibernate valida el esquema) sin sustituir nada por un mock. Es lo que
 * detecta un {@code @Service} que no llega a ser bean antes de empaquetar. Cada fase que anade un
 * servicio anade aqui su comprobacion. {@code KeycloakEventsIT} es el otro contexto completo, con
 * Keycloak de verdad, y solo corre en {@code verify}.
 */
@SpringBootTest(properties = {
        // No hay broker en este test. El cableado del canal se comprueba en MessagingLayerTest.
        "app.rabbitmq.enabled=false",
        // Nada que corra solo: ni el lector de Keycloak, ni el despachador, ni el cierre de rafagas,
        // ni la purga. Cada uno se prueba llamandolo desde su test.
        "app.keycloak.events.enabled=false",
        "app.notification.delivery.enabled=false",
        "app.notification.delivery.immediate-dispatch=false",
        "app.notification.burst.close-enabled=false",
        "app.notification.retention.purge-enabled=false",
        // El canal de correo se monta (no abre conexion hasta el primer envio): asi se ve que el
        // despachador lo indexa por nombre.
        "app.notification.email.enabled=true"
})
class MtoNotificationApplicationTests extends PostgreSQLTestContainer {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private Tracer tracer;

    @Test
    void contextLoads() {
        assertNotNull(context.getBean(CorrelationIdFilter.class));
    }

    /**
     * Con {@code spring-boot-starter-security} en el classpath, quedarse sin
     * {@code SecurityFilterChain} propio devuelve el control a la cadena por defecto de Boot:
     * formulario de login, CSRF y sesiones delante de una API.
     */
    @Test
    void exactlyOneSecurityFilterChainIsPublished() {
        assertEquals(1, context.getBeanNamesForType(SecurityFilterChain.class).length);
    }

    @Test
    void theTracingBridgeIsInTheContext() {
        assertNotNull(tracer);
    }

    @Test
    void everyServiceOfTheIngestionAndTheRulesIsABean() {
        for (Class<?> service : List.of(InboxMessageService.class, SourceEventProcessor.class, SourceEventHandler.class,
                ActivityIngestor.class, BurstAggregator.class, RuleRepository.class, RuleEngine.class, ThrottleGate.class,
                NotificationFactory.class, DerivedEventDetector.class)) {
            assertNotNull(context.getBean(service), service.getSimpleName());
        }
        assertEquals(List.of("master-data"), context.getBeansOfType(ActivitySourceAdapter.class).values().stream()
                .map(ActivitySourceAdapter::sourceId).sorted().toList(), "las fuentes de esta fase");
        assertFalse(context.getBean(RuleRepository.class).rules().isEmpty(), "el YAML de reglas se carga al arrancar");
    }

    @Test
    void everyServiceOfTheDeliveriesTheReaderAndTheApiIsABean() {
        for (Class<?> service : List.of(DeliveryDispatcher.class, DeliveryRelayService.class, DeliveryDispatchTrigger.class,
                AudienceResolver.class, KeycloakEventsPoller.class, KeycloakEventsClient.class, KeycloakDirectoryClient.class,
                SourceCursorService.class, InboxQueryService.class, ActivityQueryService.class, AdminService.class,
                RetentionPurge.class, InboxController.class, ActivityController.class, AccessController.class, AdminController.class)) {
            assertNotNull(context.getBean(service), service.getSimpleName());
        }
        assertEquals(List.of("email"), context.getBeansOfType(DeliveryChannel.class).values().stream()
                .map(DeliveryChannel::channel).toList(), "los canales que empujan en esta fase");
        assertEquals(2, context.getBean(SourceCursorService.class).all().size(), "V1 siembra las dos marcas del lector");
        assertTrue(context.getBean(SourceCursorService.class).all().stream()
                .map(cursor -> cursor.getKind()).toList().containsAll(List.of(SourceKind.KEYCLOAK_LOGIN, SourceKind.KEYCLOAK_ADMIN)));
    }

    @Test
    void whatIsSwitchedOffStaysOut() {
        assertEquals(0, context.getBeanNamesForType(MasterDataSourceConsumer.class).length, "sin broker no hay consumidor");
        assertEquals(0, context.getBeanNamesForType(KeycloakPollingConfiguration.class).length, "sin sondeo planificado");
    }
}
