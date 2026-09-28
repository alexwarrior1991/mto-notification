package com.alejandro.mtonotification;

import com.alejandro.mtonotification.configuration.web.CorrelationIdFilter;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * El unico {@code @SpringBootTest}: arranca el contexto entero contra un PostgreSQL real sin
 * sustituir nada por un mock. Es lo que detecta un {@code @Service} que no llega a ser bean antes
 * de empaquetar. Cada fase que anade un servicio anade aqui su comprobacion.
 */
@SpringBootTest(properties = {
        // No hay broker en este test. El cableado del canal se comprueba en MessagingLayerTest.
        "app.rabbitmq.enabled=false"
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
}
