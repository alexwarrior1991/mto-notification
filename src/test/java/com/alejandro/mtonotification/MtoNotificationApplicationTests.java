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
import com.alejandro.mtonotification.configuration.keycloak.KeycloakCircuitBreakerExecutorConfiguration;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakClientConfiguration;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakPollingConfiguration;
import com.alejandro.mtonotification.configuration.scheduling.SchedulingConfiguration;
import com.alejandro.mtonotification.configuration.web.CorrelationIdFilter;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.ConfigurationSourceConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.FieldSourceConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.MasterDataSourceConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.ResourcesSourceConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.UsersSourceConsumer;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.web.controller.AccessController;
import com.alejandro.mtonotification.infrastructure.web.controller.ActivityController;
import com.alejandro.mtonotification.infrastructure.web.controller.AdminController;
import com.alejandro.mtonotification.infrastructure.web.controller.InboxController;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.thread.Threading;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    /**
     * La configuracion de la aplicacion enciende los hilos virtuales (Tomcat, los @Scheduled, los
     * consumidores y el circuito de Keycloak los toman de esta misma condicion), y el pool de
     * conexiones, que pasa a ser el limite real, es el configurado: 10 por defecto en application.yaml.
     */
    @Test
    void theApplicationRunsOnVirtualThreadsWithTheConfiguredConnectionPool() throws Exception {
        assertTrue(Threading.VIRTUAL.isActive(context.getEnvironment()), "spring.threads.virtual.enabled");
        assertEquals(context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class),
                context.getBean(DataSource.class).unwrap(HikariDataSource.class).getMaximumPoolSize());

        StandardEnvironment defaults = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(defaults.getPropertySources()::addLast);
        assertEquals(10, defaults.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class),
                "el valor por defecto de DATABASE_POOL_MAX_SIZE");
        assertEquals(4, defaults.getProperty("spring.task.scheduling.pool.size", Integer.class),
                "el pool de los @Scheduled, el de SchedulingConfiguration, con hilos virtuales y sin ellos");
    }

    /**
     * Dos pasadas {@code fixedDelay} corren a la vez en el planificador de {@link SchedulingConfiguration},
     * con hilos virtuales y sin ellos: cada una espera a que la otra haya empezado, asi que con un solo
     * hilo la segunda no empezaria nunca mientras la primera espera. Es lo que no hacia el planificador
     * de Boot con hilos virtuales, que corre todas las tareas {@code fixedDelay} en su unico hilo: el lector
     * de Keycloak paraba el cierre de rafagas y el despachador.
     */
    @Test
    void theScheduledPassesRunAtTheSameTimeWithVirtualThreadsAndWithout() {
        for (boolean virtual : List.of(true, false)) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                    .withUserConfiguration(SchedulingConfiguration.class, TwoWaitingPasses.class)
                    .withPropertyValues("spring.threads.virtual.enabled=" + virtual, "spring.task.scheduling.pool.size=4")
                    .run(started -> {
                        TwoWaitingPasses passes = started.getBean(TwoWaitingPasses.class);
                        try {
                            assertTrue(passes.bothStarted.await(10, TimeUnit.SECONDS),
                                    "las dos pasadas a la vez, con spring.threads.virtual.enabled=" + virtual);
                            assertEquals(Set.of(virtual), passes.onVirtualThreads, "hilos virtuales solo con la bandera");
                        } finally {
                            passes.release.countDown();
                        }
                    });
        }
    }

    /** El executor del circuito {@code keycloak} no es un bean: lo cierra su configuracion con el contexto. */
    @Test
    void theExecutorOfTheKeycloakCircuitIsShutDownWithTheContext() {
        KeycloakCircuitBreakerExecutorConfiguration configuration = new KeycloakCircuitBreakerExecutorConfiguration();
        Resilience4JCircuitBreakerFactory factory = mock(Resilience4JCircuitBreakerFactory.class);
        configuration.keycloakCircuitBreakerExecutor().customize(factory);
        ArgumentCaptor<ExecutorService> executor = ArgumentCaptor.forClass(ExecutorService.class);
        verify(factory).configureExecutorService(executor.capture());

        assertFalse(executor.getValue().isShutdown());
        configuration.destroy();
        assertTrue(executor.getValue().isShutdown());
    }

    /**
     * Sin {@code @Configuration} a proposito: una clase anidada con ella sustituiria la configuracion
     * de este {@code @SpringBootTest}. {@code @EnableScheduling} basta para que el runner la procese.
     */
    @EnableScheduling
    static class TwoWaitingPasses {

        final CountDownLatch bothStarted = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Set<Boolean> onVirtualThreads = ConcurrentHashMap.newKeySet();

        @Scheduled(fixedDelay = 60_000)
        void first() throws InterruptedException {
            pass();
        }

        @Scheduled(fixedDelay = 60_000)
        void second() throws InterruptedException {
            pass();
        }

        private void pass() throws InterruptedException {
            onVirtualThreads.add(Thread.currentThread().isVirtual());
            bothStarted.countDown();
            release.await(30, TimeUnit.SECONDS);
        }
    }

    /** Una llamada por el circuito {@code keycloak} espera en un hilo virtual, no en el pool de plataforma del circuito. */
    @Test
    void theKeycloakCircuitCallsOnAVirtualThread() {
        String thread = context.getBean(CircuitBreakerFactory.class).create(KeycloakClientConfiguration.KEYCLOAK_CIRCUIT_BREAKER)
                .run(() -> Thread.currentThread().isVirtual() + ":" + Thread.currentThread().getName());
        assertTrue(thread.startsWith("true:keycloak-circuit-"), thread);
    }

    @Test
    void everyServiceOfTheIngestionAndTheRulesIsABean() {
        for (Class<?> service : List.of(InboxMessageService.class, SourceEventProcessor.class, SourceEventHandler.class,
                ActivityIngestor.class, BurstAggregator.class, RuleRepository.class, RuleEngine.class, ThrottleGate.class,
                NotificationFactory.class)) {
            assertNotNull(context.getBean(service), service.getSimpleName());
        }
        assertEquals(List.of("configuration", "field", "maintenance", "master-data", "resources", "stock", "users"), context.getBeansOfType(ActivitySourceAdapter.class).values().stream()
                .map(ActivitySourceAdapter::sourceId).sorted().toList(), "las fuentes de RabbitMQ hasta esta fase");
        assertEquals(2, context.getBeansOfType(DerivedEventDetector.class).size(), "la racha de accesos y el correlador de usuarios");
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
        assertEquals(0, context.getBeanNamesForType(ConfigurationSourceConsumer.class).length);
        assertEquals(0, context.getBeanNamesForType(UsersSourceConsumer.class).length);
        assertEquals(0, context.getBeanNamesForType(FieldSourceConsumer.class).length);
        assertEquals(0, context.getBeanNamesForType(ResourcesSourceConsumer.class).length);
        assertEquals(0, context.getBeanNamesForType(KeycloakPollingConfiguration.class).length, "sin sondeo planificado");
    }
}
