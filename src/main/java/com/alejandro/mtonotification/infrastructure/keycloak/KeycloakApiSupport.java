package com.alejandro.mtonotification.infrastructure.keycloak;

import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * Lo que comparten los dos clientes: cada llamada corre dentro del circuito {@code keycloak} y todo
 * fallo (red, 5xx, circuito abierto, 401/403 de la cuenta de servicio) sale como
 * {@link DirectoryUnavailableException}, salvo un 404, que quien llama trata como «no existe».
 */
final class KeycloakApiSupport {

    private KeycloakApiSupport() {
    }

    static <T> T call(CircuitBreaker circuitBreaker, String operation, Supplier<T> request) {
        return circuitBreaker.run(request, failure -> {
            throw translate(operation, failure);
        });
    }

    static RuntimeException translate(String operation, Throwable failure) {
        if (failure instanceof NotFound notFound) {
            return notFound;
        }
        if (failure instanceof HttpClientErrorException.NotFound) {
            return new NotFound();
        }
        if (failure instanceof HttpClientErrorException.Unauthorized || failure instanceof HttpClientErrorException.Forbidden) {
            return new DirectoryUnavailableException("Keycloak refused the service account for " + operation
                    + ": check the realm-management roles of mto-notification-svc (" + failure.getMessage() + ")", failure);
        }
        if (failure instanceof RestClientException || failure instanceof RuntimeException) {
            return new DirectoryUnavailableException("Keycloak did not answer " + operation + ": " + failure.getMessage(), failure);
        }
        return new DirectoryUnavailableException("Keycloak failed " + operation + ": " + failure.getMessage(), failure);
    }

    /** Un 404 no es un fallo de la fuente: el recurso no existe. */
    static final class NotFound extends RuntimeException {
        NotFound() {
            super("not found");
        }
    }
}
