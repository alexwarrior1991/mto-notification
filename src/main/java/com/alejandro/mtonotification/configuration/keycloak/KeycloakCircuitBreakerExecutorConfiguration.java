package com.alejandro.mtonotification.configuration.keycloak;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.thread.Threading;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * El hilo en el que el circuito {@code keycloak} hace cada llamada a la Admin API.
 *
 * <p>Con el {@code TimeLimiter}, Spring Cloud CircuitBreaker no llama en el hilo de quien pide: manda
 * la llamada a su executor y espera el resultado con el tiempo maximo. Ese executor es por defecto un
 * {@code Executors.newCachedThreadPool()} de hilos de plataforma, y {@code spring.threads.virtual.enabled}
 * no lo toca: con los hilos virtuales activos, cada lectura de eventos y cada expansion de una audiencia
 * seguia esperando en un hilo del sistema operativo. Aqui va a un hilo virtual nuevo,
 * {@code keycloak-circuit-N} en los logs. Es la copia de {@code StockCircuitBreakerExecutorConfiguration}
 * de mto-maintenance.</p>
 *
 * <p>Nada mas cambia: el {@code TimeLimiter} interrumpe el hilo virtual igual que a uno de plataforma,
 * y el token de la cuenta de servicio ya se pedia fuera de cualquier peticion HTTP
 * ({@link KeycloakClientConfiguration}). Solo con los hilos virtuales activos. El executor no es un bean
 * a proposito: un {@code Executor} en el contexto haria que Boot dejara de crear su
 * {@code applicationTaskExecutor}. Por eso lo cierra esta configuracion al cerrarse el contexto: una
 * llamada en vuelo se interrumpe, como la interrumpiria el {@code TimeLimiter}, y ninguna nueva
 * arranca.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnThreading(Threading.VIRTUAL)
public class KeycloakCircuitBreakerExecutorConfiguration implements DisposableBean {

    static final String THREAD_NAME_PREFIX = "keycloak-circuit-";

    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(THREAD_NAME_PREFIX, 0).factory());

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> keycloakCircuitBreakerExecutor() {
        return factory -> factory.configureExecutorService(executor);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
