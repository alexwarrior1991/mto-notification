package com.alejandro.mtonotification.configuration.security;

import com.alejandro.mtonotification.infrastructure.web.NotificationApiPaths;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Cadena de filtros de la API. La aplicación es un <em>resource server</em>: no emite tokens ni
 * guarda usuarios, solo valida los JWT que emite Keycloak y traduce sus roles a autoridades.
 *
 * <p>A diferencia de sus hermanos, aquí el permiso no lo decide el verbo sino el recurso: la
 * bandeja es de quien la lee, el registro de actividad tiene su permiso, los accesos —que llevan
 * usuario e IP— el suyo, y la administración el suyo. Lo que quede bajo la API sin regla propia se
 * deniega: un endpoint nuevo tiene que declarar su permiso antes de existir.</p>
 *
 * <p>No hay CORS: todo navegador llega por {@code mto-gateway}, que lo resuelve y quita
 * {@code Origin} antes de llamar. Sin {@code .cors()} ni {@code OPTIONS} abierto, un preflight que
 * llegara aquí directamente pide token como cualquier otra petición.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    /** Raíz de los recursos de negocio. Coincide con el {@code @RequestMapping} de cada controlador. */
    static final String API = NotificationApiPaths.BASE;

    private static final String[] API_DOCS = {
            "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui/**", "/swagger-ui.html"
    };

    private final SecurityProperties securityProperties;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfiguration(
            SecurityProperties securityProperties,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) {
        this.securityProperties = securityProperties;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public KeycloakJwtAuthenticationConverter jwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter(securityProperties);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> {
                    // Las sondas de arranque y de vida las consulta el orquestador, que no tiene
                    // token. El resto del detalle de health lo gobierna
                    // 'management.endpoint.health.show-details'.
                    authorize.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info"
                    ).permitAll();

                    // La documentación describe la superficie completa de la API. En un entorno
                    // desplegado es el mejor mapa posible para quien busque un hueco, así que se
                    // abre solo donde la configuración lo pide.
                    if (securityProperties.exposeApiDocs()) {
                        authorize.requestMatchers(API_DOCS).permitAll();
                    }

                    // Todo Actuator cerrado salvo health e info. Lo que modifica va antes que la
                    // regla general y con su propio permiso: en Actuator las @WriteOperation viajan
                    // por POST y las @DeleteOperation por DELETE; el resto es lectura.
                    authorize.requestMatchers(HttpMethod.POST, "/actuator/**").hasRole(SecurityRoles.OPS_WRITE);
                    authorize.requestMatchers(HttpMethod.DELETE, "/actuator/**").hasRole(SecurityRoles.OPS_WRITE);
                    authorize.requestMatchers("/actuator/**").hasRole(SecurityRoles.OPS_METRICS);

                    // El orden importa: cada petición se resuelve con la primera regla que encaja.
                    // La bandeja es de quien la lee: leer y marcar como leida son el mismo permiso,
                    // porque lo que se marca es el estado propio de esa persona.
                    authorize.requestMatchers(API + NotificationApiPaths.INBOX + "/**").hasRole(SecurityRoles.NOTIFICATION_INBOX);
                    // El registro y los accesos son solo lectura. HEAD lo sirve el mismo handler
                    // que GET y revela si un recurso existe, así que va con la misma regla.
                    authorize.requestMatchers(HttpMethod.GET, API + NotificationApiPaths.ACTIVITY + "/**").hasRole(SecurityRoles.NOTIFICATION_ACTIVITY_READ);
                    authorize.requestMatchers(HttpMethod.HEAD, API + NotificationApiPaths.ACTIVITY + "/**").hasRole(SecurityRoles.NOTIFICATION_ACTIVITY_READ);
                    authorize.requestMatchers(HttpMethod.GET, API + NotificationApiPaths.ACCESS + "/**").hasRole(SecurityRoles.NOTIFICATION_ACCESS_READ);
                    authorize.requestMatchers(HttpMethod.HEAD, API + NotificationApiPaths.ACCESS + "/**").hasRole(SecurityRoles.NOTIFICATION_ACCESS_READ);
                    authorize.requestMatchers(API + NotificationApiPaths.ADMIN + "/**").hasRole(SecurityRoles.NOTIFICATION_ADMIN);
                    // Lo que quede bajo la API sin regla propia se deniega, no se abre a cualquier
                    // autenticado: un recurso nuevo declara su permiso o no existe.
                    authorize.requestMatchers(API + "/**").denyAll();

                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                )
                .build();
    }

    /**
     * Se construye con el JWK Set en lugar de con el descubrimiento por issuer: {@code
     * JwtDecoders.fromIssuerLocation()} hace una llamada HTTP bloqueante al crear el bean, de modo
     * que la aplicación no arranca si Keycloak todavía no sirve el {@code
     * .well-known/openid-configuration}. Con el JWK Set la descarga es perezosa —la primera vez que
     * llega un token— y un reinicio simultáneo de los dos servicios deja de ser un fallo de
     * arranque.
     */
    @Bean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties properties) {
        OAuth2ResourceServerProperties.Jwt jwtProperties = properties.getJwt();
        String issuerUri = jwtProperties.getIssuerUri();

        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder
                .withJwkSetUri(resolveJwkSetUri(jwtProperties))
                .build();

        // Se parte del validador por defecto en vez de reemplazarlo: incluye la comprobación de
        // emisor y de vigencia, y hereda las que Spring Security añada en versiones futuras.
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                audienceValidator()
        ));

        return jwtDecoder;
    }

    /**
     * Keycloak publica el JWK Set en una ruta fija bajo el realm. Se respeta {@code jwk-set-uri} si
     * está configurado, para no atar la aplicación a esa convención.
     */
    static String resolveJwkSetUri(OAuth2ResourceServerProperties.Jwt jwtProperties) {
        if (StringUtils.hasText(jwtProperties.getJwkSetUri())) {
            return jwtProperties.getJwkSetUri();
        }

        return jwtProperties.getIssuerUri() + "/protocol/openid-connect/certs";
    }

    /**
     * Que {@code required-audience} esté relleno lo garantiza {@link SecurityProperties} en el
     * arranque, así que aquí no hay ninguna rama que deje pasar el token por falta de
     * configuración: o se valida la audiencia, o se ha desactivado de forma explícita.
     */
    private OAuth2TokenValidator<Jwt> audienceValidator() {
        if (!securityProperties.audienceValidationEnabled()) {
            return jwt -> OAuth2TokenValidatorResult.success();
        }

        return new JwtAudienceValidator(securityProperties.requiredAudience());
    }
}
