package com.alejandro.mtonotification.configuration.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * No existe un interruptor para apagar la seguridad: lo que cambia entre entornos son estas
 * properties, no la existencia de la cadena de filtros. Un flag de ese tipo tampoco desactivaría
 * nada — con {@code spring-boot-starter-security} en el classpath, quedarse sin
 * {@code SecurityFilterChain} propio devuelve el control a la cadena por defecto de Boot, con
 * formulario de login, CSRF y sesiones.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @NotBlank String clientId,
        @NotBlank String principalClaim,
        boolean audienceValidationEnabled,
        String requiredAudience,
        boolean exposeApiDocs,
        @Valid Cors cors
) {

    /**
     * Sin esta comprobación, un {@code KEYCLOAK_AUDIENCE} vacío apagaba la validación de audiencia
     * en tiempo de petición y sin dejar rastro en el log: la API pasaba a aceptar cualquier token
     * emitido por el realm, incluido el del frontal de otra aplicación. Mejor no arrancar.
     */
    @AssertTrue(message = "app.security.required-audience es obligatorio cuando "
            + "app.security.audience-validation-enabled es true")
    public boolean isAudienceConfigurationConsistent() {
        return !audienceValidationEnabled || (requiredAudience != null && !requiredAudience.isBlank());
    }

    /**
     * La API es stateless y se autentica con la cabecera {@code Authorization}, así que no hay
     * cookies que enviar y {@code allowCredentials} solo ampliaría la superficie. Con credenciales
     * activas, además, Spring rechaza el comodín en tiempo de ejecución, de modo que un
     * {@code allowed-origins: "*"} puesto para salir del paso rompe cada preflight en vez de
     * aflojar la política.
     */
    public record Cors(
            @NotEmpty List<String> allowedOrigins,
            @NotEmpty List<String> allowedMethods,
            @NotEmpty List<String> allowedHeaders,
            List<String> exposedHeaders,
            boolean allowCredentials,
            long maxAge
    ) {

        @AssertTrue(message = "app.security.cors.allowed-origins no admite el comodín '*': "
                + "enumérense los orígenes reales de cada entorno")
        public boolean isOriginListExplicit() {
            return allowedOrigins == null || !allowedOrigins.contains("*");
        }
    }
}
