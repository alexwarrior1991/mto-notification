package com.alejandro.mtonotification.configuration.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Un único identificador por petición: el que trae la cabecera {@code X-Correlation-Id} —normalmente
 * puesto por el gateway— o uno nuevo si falta o no es válido. Se devuelve en la respuesta, se pone
 * en el MDC para que salga en cada línea de log y lo recoge el {@code ApiErrorResponse} de
 * cualquier error. Es también la referencia que guarda el registro de actividad para atar una
 * escritura a la petición que la causó.
 *
 * <p>Va el primero de todos ({@link Ordered#HIGHEST_PRECEDENCE}) para que el MDC esté puesto antes
 * de que registre nada Spring Security ni el propio despachador.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(CorrelationIdProperties.class)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Clave en el MDC. Se referencia como {@code %X{correlationId}} en el patrón de log. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Lo que llega de fuera se acota antes de reutilizarlo: este valor acaba en cada línea de log,
     * de modo que un CR/LF permitiría partir la línea e inventarse entradas —de auditoría, además—.
     * Un valor inválido no se rechaza con un 400: se sustituye por uno nuevo.
     */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final CorrelationIdProperties properties;

    public CorrelationIdFilter(CorrelationIdProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(properties.headerName()));

        response.setHeader(properties.headerName(), correlationId);
        MDC.put(MDC_KEY, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // remove y no clear(): el MDC no es nuestro, solo esta clave lo es. Y va en finally
            // porque los hilos se reutilizan.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(String inboundValue) {
        if (inboundValue != null
                && inboundValue.length() <= properties.maxLength()
                && VALID_ID.matcher(inboundValue).matches()) {
            return inboundValue;
        }

        return UUID.randomUUID().toString();
    }
}
