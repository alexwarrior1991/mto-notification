package com.alejandro.mtonotification.configuration.keycloak;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * La Admin API de Keycloak ({@code app.keycloak.*}): el lector de eventos y el directorio, los dos
 * con la cuenta de servicio del registro {@code clientRegistrationId}.
 */
@Validated
@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(
        @NotBlank String baseUrl,
        @NotBlank String realm,
        @NotBlank String clientRegistrationId,
        CircuitBreaker circuitBreaker,
        Events events,
        Directory directory
) {

    public KeycloakProperties {
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        circuitBreaker = circuitBreaker == null
                ? new CircuitBreaker(10, 5, 50, Duration.ofSeconds(30), Duration.ofSeconds(15)) : circuitBreaker;
        events = events == null ? new Events(true, true, true, null, null, 0, 0, null, null, null, null, null, null) : events;
        directory = directory == null ? new Directory(null, 0, null) : directory;
    }

    /** La raiz de la Admin API del realm: {@code <base>/admin/realms/<realm>}. */
    public String adminRealmUrl() {
        return baseUrl + "/admin/realms/" + realm;
    }

    public record CircuitBreaker(int slidingWindowSize, int minimumNumberOfCalls, int failureRateThreshold,
                                 Duration waitDurationInOpenState, Duration timeout) {
    }

    /**
     * @param enabled              el lector entero
     * @param loginEnabled         los eventos de acceso
     * @param adminEnabled         los eventos de administracion
     * @param pollInterval         cada cuanto sondea
     * @param initialDelay         cuanto espera tras arrancar
     * @param pageSize             {@code max} de cada pagina
     * @param maxPagesPerPoll      paginas de un tramo: con {@code pageSize}, los eventos que se tienen en memoria a la vez
     * @param overlap              cuanto se relee por detras de la marca
     * @param initialLookback      desde cuando se lee la primera vez
     * @param leaseTtl             cuanto dura el arrendamiento de una fuente
     * @param usersServiceClientId el cliente de mto-users: sus cambios son «de la aplicacion»
     * @param maxPassDuration      cuanto dura como mucho una pasada con atraso; la siguiente sigue donde quedo
     * @param retry                los reintentos de un evento que fallo y quedo FAILED en el inbox
     */
    public record Events(boolean enabled, boolean loginEnabled, boolean adminEnabled, Duration pollInterval,
                         Duration initialDelay, int pageSize, int maxPagesPerPoll, Duration overlap,
                         Duration initialLookback, Duration leaseTtl, String usersServiceClientId,
                         Duration maxPassDuration, Retry retry) {
        public Events {
            pollInterval = pollInterval == null ? Duration.ofSeconds(20) : pollInterval;
            initialDelay = initialDelay == null ? Duration.ofSeconds(15) : initialDelay;
            pageSize = pageSize <= 0 ? 100 : Math.min(pageSize, 500);
            maxPagesPerPoll = maxPagesPerPoll <= 0 ? 50 : maxPagesPerPoll;
            overlap = overlap == null ? Duration.ofMinutes(2) : overlap;
            initialLookback = initialLookback == null ? Duration.ofHours(1) : initialLookback;
            leaseTtl = leaseTtl == null ? Duration.ofMinutes(2) : leaseTtl;
            usersServiceClientId = usersServiceClientId == null || usersServiceClientId.isBlank() ? "mto-users-svc" : usersServiceClientId.trim();
            maxPassDuration = maxPassDuration == null || maxPassDuration.isNegative() || maxPassDuration.isZero()
                    ? Duration.ofMinutes(1) : maxPassDuration;
            retry = retry == null ? new Retry(0, null, null, 0) : retry;
        }

        /** Los eventos de un tramo: lo que se lee, se guarda en memoria y se procesa de una vez. */
        public int chunkSize() {
            return pageSize * maxPagesPerPoll;
        }
    }

    /**
     * @param maxAttempts intentos en total, contando el primero; agotados, el evento queda FAILED para siempre
     * @param backoff     espera tras el primer fallo; se dobla en cada uno
     * @param maxBackoff  tope de esa espera
     * @param batchSize   cuantos fallidos se reintentan por pasada y fuente
     */
    public record Retry(int maxAttempts, Duration backoff, Duration maxBackoff, int batchSize) {
        public Retry {
            maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
            backoff = backoff == null || backoff.isNegative() ? Duration.ofMinutes(1) : backoff;
            maxBackoff = maxBackoff == null ? Duration.ofMinutes(30) : maxBackoff;
            maxBackoff = maxBackoff.compareTo(backoff) < 0 ? backoff : maxBackoff;
            batchSize = batchSize <= 0 ? 50 : batchSize;
        }
    }

    /** @param cacheTtl cuanto se recuerdan las direcciones de una audiencia; @param profilePrefix el de los perfiles del dominio */
    public record Directory(Duration cacheTtl, int pageSize, String profilePrefix) {
        public Directory {
            cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
            pageSize = pageSize <= 0 ? 200 : Math.min(pageSize, 500);
            profilePrefix = profilePrefix == null || profilePrefix.isBlank() ? "mto-" : profilePrefix.trim();
        }
    }
}
