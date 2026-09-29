package com.alejandro.mtonotification.configuration.keycloak;

import com.alejandro.mtonotification.infrastructure.keycloak.RestKeycloakDirectoryClient;
import com.alejandro.mtonotification.infrastructure.keycloak.RestKeycloakEventsClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cliente HTTP hacia la Admin API de Keycloak con la cuenta de servicio (client_credentials).
 * El gestor de tokens se construye aqui y no se pide al contexto: el que registra Spring Security
 * por defecto esta ligado a la peticion HTTP, y estas llamadas salen del planificador y del
 * despachador, sin peticion. Mismo patron que mto-maintenance hacia mto-stock.
 */
@Configuration
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakClientConfiguration.class);

    public static final String KEYCLOAK_CIRCUIT_BREAKER = "keycloak";

    /** Principal nominal de la cuenta de servicio: solo identifica la autorizacion en el servicio de clientes. */
    static final String SERVICE_PRINCIPAL = "mto-notification";

    @Bean
    public RestClient keycloakRestClient(RestClient.Builder builder, KeycloakProperties properties,
                                         ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                         ObjectProvider<OAuth2AuthorizedClientService> authorizedClients) {
        RestClient.Builder keycloakBuilder = builder.clone().baseUrl(properties.adminRealmUrl());
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        OAuth2AuthorizedClientService clients = authorizedClients.getIfAvailable();
        OAuth2AuthorizedClientManager manager = registrations == null || clients == null ? null
                : new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        if (manager == null) {
            LOGGER.warn("No OAuth2 client registration is configured: calls to the Keycloak Admin API will go out without a bearer token");
        } else {
            keycloakBuilder.requestInterceptor((request, body, execution) -> {
                OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
                        .withClientRegistrationId(properties.clientRegistrationId())
                        .principal(SERVICE_PRINCIPAL)
                        .build());
                if (client != null) {
                    request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
                }
                return execution.execute(request, body);
            });
        }
        return keycloakBuilder.build();
    }

    /** Umbrales del circuito 'keycloak', desde {@code app.keycloak.circuit-breaker}. */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> keycloakCircuitBreakerCustomizer(KeycloakProperties properties) {
        KeycloakProperties.CircuitBreaker settings = properties.circuitBreaker();
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(settings.slidingWindowSize())
                        .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                        .failureRateThreshold(settings.failureRateThreshold())
                        .waitDurationInOpenState(settings.waitDurationInOpenState())
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(settings.timeout())
                        .build()), KEYCLOAK_CIRCUIT_BREAKER);
    }

    @Bean
    public RestKeycloakEventsClient keycloakEventsClient(RestClient keycloakRestClient, JsonMapper jsonMapper,
                                                         CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        return new RestKeycloakEventsClient(keycloakRestClient, jsonMapper, circuitBreakerFactory.create(KEYCLOAK_CIRCUIT_BREAKER));
    }

    @Bean
    public RestKeycloakDirectoryClient keycloakDirectoryClient(RestClient keycloakRestClient,
                                                               CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        return new RestKeycloakDirectoryClient(keycloakRestClient, circuitBreakerFactory.create(KEYCLOAK_CIRCUIT_BREAKER));
    }
}
