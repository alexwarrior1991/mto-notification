package com.alejandro.mtonotification.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de los tests que necesitan un PostgreSQL real con las migraciones de Flyway aplicadas.
 *
 * <p>Por defecto levanta un contenedor {@code postgres:17-alpine}, la misma versión que corre en
 * {@code mto-platform}, para que CI y local no prueben contra motores distintos. Si no hay Docker
 * pero sí un PostgreSQL a mano, {@code TEST_DATABASE_URL} (con {@code TEST_DATABASE_USERNAME} y
 * {@code TEST_DATABASE_PASSWORD}) apunta a él y el contenedor no se arranca: es lo que permite
 * ejecutar la suite en un entorno sin daemon de Docker.</p>
 *
 * <p>Sin Docker ni variable, la clase entera se omite. Lo decide {@link DatabaseAvailable} antes de
 * montar el contexto: una suposición fallida dentro del {@code @DynamicPropertySource} llegaría
 * envuelta en un fallo de carga del contexto y el test contaría como error, no como omitido. Por eso
 * las clases que heredan de aquí no llevan {@code @Testcontainers(disabledWithoutDocker = true)}, que
 * además las apagaría aunque {@code TEST_DATABASE_URL} estuviera puesta.</p>
 *
 * <p>El contenedor es estático y se arranca una sola vez por JVM: cada contexto de Spring aplica las
 * migraciones sobre la misma base, que Flyway deja como no-op a partir de la segunda vez.</p>
 */
@ExtendWith(PostgreSQLTestContainer.DatabaseAvailable.class)
public abstract class PostgreSQLTestContainer {

    private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");

    private static PostgreSQLContainer<?> container;

    protected static void registerPostgreSQLProperties(DynamicPropertyRegistry registry) {
        if (hasExternalDatabase()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> envOrEmpty("TEST_DATABASE_USERNAME"));
            registry.add("spring.datasource.password", () -> envOrEmpty("TEST_DATABASE_PASSWORD"));
        } else {
            PostgreSQLContainer<?> postgres = container();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static boolean hasExternalDatabase() {
        return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("mto_notification_test")
                    .withUsername("mto_notification")
                    .withPassword("mto_notification");
            container.start();
        }
        return container;
    }

    private static String envOrEmpty(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }

    /** Omite la clase cuando no hay contra qué PostgreSQL ejecutarla. */
    static final class DatabaseAvailable implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (hasExternalDatabase()) {
                return ConditionEvaluationResult.enabled("TEST_DATABASE_URL points at a PostgreSQL");
            }
            return DockerClientFactory.instance().isDockerAvailable()
                    ? ConditionEvaluationResult.enabled("Docker is available for Testcontainers")
                    : ConditionEvaluationResult.disabled("Neither Docker nor TEST_DATABASE_URL is available: skipping the PostgreSQL-backed test");
        }
    }
}
