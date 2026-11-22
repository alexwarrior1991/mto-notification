package com.alejandro.mtonotification.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

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
 * <p>El contenedor es estático y se arranca una sola vez por JVM.</p>
 *
 * <p><b>Cada clase trabaja en su propio esquema</b> ({@code test_<clase>}), que se borra y se vuelve a
 * crear al arrancar su contexto, y Flyway lo migra entero. Estos tests cuentan con las tablas vacías
 * (la purga, las marcas del lector, el reclamo de entregas y los recuentos por fuente son de la tabla
 * entera, no de unas filas propias), y eso solo es verdad si las tablas son de la clase:
 * {@code KeycloakEventsIT} confirma lo que escribe, y contra una base fija ({@code TEST_DATABASE_URL})
 * sus filas quedaban en el esquema que compartían todas y rompían la siguiente ejecución, la suya
 * incluida. Así el orden de las clases no importa, la suite se repite sobre la misma base y nunca se
 * toca {@code public} ni nada que no sea un esquema {@code test_*} de esta suite.</p>
 */
@ExtendWith(PostgreSQLTestContainer.DatabaseAvailable.class)
public abstract class PostgreSQLTestContainer {

    private static final String EXTERNAL_URL = System.getenv("TEST_DATABASE_URL");

    private static final String SCHEMA_PREFIX = "test_";

    /** Los esquemas que esta JVM ya recreo. */
    private static final Set<String> RECREATED = new HashSet<>();

    private static PostgreSQLContainer<?> container;

    /**
     * Apunta el contexto de {@code testClass} a SU esquema de la base de test, recien creado: las
     * tablas de una clase estan vacias al empezar y lo que escribe no lo ve ninguna otra.
     */
    protected static void registerPostgreSQLProperties(DynamicPropertyRegistry registry, Class<?> testClass) {
        String schema = schemaOf(testClass);
        String url;
        String username;
        String password;
        if (hasExternalDatabase()) {
            url = EXTERNAL_URL;
            username = envOrEmpty("TEST_DATABASE_USERNAME");
            password = envOrEmpty("TEST_DATABASE_PASSWORD");
        } else {
            PostgreSQLContainer<?> postgres = container();
            url = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        }
        recreateOncePerRun(url, username, password, schema);
        String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /** {@code test_} y el nombre de la clase en snake_case: {@code test_keycloak_events_it}. */
    static String schemaOf(Class<?> testClass) {
        String snake = testClass.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        return SCHEMA_PREFIX + snake;
    }

    /**
     * Borra y vuelve a crear el esquema de una clase, una vez por JVM (un contexto que Spring
     * recargase a mitad de clase no debe vaciarle las tablas). Nunca toca otro esquema: el nombre
     * lleva el prefijo de la suite y, si existe, tiene que ser uno que migro Flyway; un esquema con ese
     * nombre que no lo sea (la variable apunta a una base que no es de test) para el test sin borrar nada.
     */
    private static synchronized void recreateOncePerRun(String url, String username, String password, String schema) {
        if (!RECREATED.add(url + "|" + schema)) {
            return;
        }
        if (!schema.matches(SCHEMA_PREFIX + "[a-z0-9_]{1,50}")) {
            throw new IllegalStateException("Not a schema of this test suite: " + schema);
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            boolean exists;
            boolean migratedByFlyway;
            try (ResultSet found = statement.executeQuery(
                    "select exists (select 1 from information_schema.schemata where schema_name = '" + schema + "'), "
                            + "exists (select 1 from information_schema.tables where table_schema = '" + schema
                            + "' and table_name = 'flyway_schema_history')")) {
                found.next();
                exists = found.getBoolean(1);
                migratedByFlyway = found.getBoolean(2);
            }
            if (exists && !migratedByFlyway) {
                throw new IllegalStateException("Schema " + schema + " exists and was not created by this test suite; "
                        + "it is not dropped. Check where TEST_DATABASE_URL points.");
            }
            statement.execute("drop schema if exists " + schema + " cascade");
            statement.execute("create schema " + schema);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not recreate the test schema " + schema, failure);
        }
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
