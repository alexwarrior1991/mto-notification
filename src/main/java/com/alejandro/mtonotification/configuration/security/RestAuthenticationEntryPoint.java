package com.alejandro.mtonotification.configuration.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Respuesta a una petición sin autenticar, en dos mitades que resuelve cada una quien sabe hacerlo.
 *
 * <p>La cabecera {@code WWW-Authenticate} la construye el entry point de Spring Security, que sigue
 * el RFC 6750: distingue «no has traído token» de «tu token no vale» y, en el segundo caso, añade
 * {@code error} y {@code error_description}. Sin esa cabecera el cliente no puede saber si le toca
 * refrescar el token o volver a autenticar, y acaba tratando cualquier 401 igual.</p>
 *
 * <p>El cuerpo lo escribe el {@code @RestControllerAdvice} de errores a través del resolver, de modo
 * que un 401 lanzado aquí —en la cadena de filtros, antes de llegar a ningún controlador— sale con
 * el mismo {@code ApiErrorResponse} que cualquier otro error de la API, en vez de obligar al cliente
 * a tener dos parsers según por dónde hubiera fallado la petición.</p>
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint bearerTokenEntryPoint = new BearerTokenAuthenticationEntryPoint();

    private final HandlerExceptionResolver handlerExceptionResolver;

    public RestAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {
        bearerTokenEntryPoint.commence(request, response, authException);

        // Sin handler: la excepción no viene de un controlador. El advice es global, así que la
        // resuelve igual. Si no la resolviera, la respuesta seguiría siendo un 401 con su cabecera,
        // solo que sin cuerpo.
        handlerExceptionResolver.resolveException(request, response, null, authException);
    }
}
