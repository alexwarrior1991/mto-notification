package com.alejandro.mtonotification.configuration.security;

import com.alejandro.mtonotification.infrastructure.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que se fija aquí es que cada recurso pida su permiso y, sobre todo, que los permisos no se
 * impliquen entre sí: administrar no abre la bandeja de nadie, y leer el registro no abre los
 * accesos, que llevan usuario e IP.
 * <p>
 * Se prueba contra un controlador sonda montado en rutas con la misma forma que las reales, no
 * contra los controladores de negocio: lo que se verifica son los patrones de la cadena de filtros,
 * y arrastrar la capa de servicio solo añadiría ruido y fragilidad.
 */
@WebMvcTest(controllers = ApiAuthorizationRulesTest.ProbeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        // El slice recoge el advice de errores, que es quien traduce el 401 de la cadena y el 403
        // de @PreAuthorize al contrato de la API.
        GlobalExceptionHandler.class,
        ApiAuthorizationRulesTest.ProbeController.class})
@TestPropertySource(properties = {
        "app.security.client-id=mto-notification-api",
        "app.security.principal-claim=preferred_username",
        "app.security.audience-validation-enabled=false",
        "app.security.expose-api-docs=false",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8082/realms/mto"
})
class ApiAuthorizationRulesTest {

    static final String INBOX = SecurityConfiguration.API + "/inbox/probe";
    static final String INBOX_READ = SecurityConfiguration.API + "/inbox/probe/read";
    static final String ACTIVITY = SecurityConfiguration.API + "/activity/probe";
    static final String ACCESS = SecurityConfiguration.API + "/access/probe";
    static final String ADMIN = SecurityConfiguration.API + "/admin/probe";
    static final String UNRULED = SecurityConfiguration.API + "/other/probe";

    /**
     * Se deja el {@code JwtDecoder} real, sin sustituir por un doble: así el contexto ejercita el
     * cableado del bean —que depende de {@code OAuth2ResourceServerProperties}— y no solo las
     * reglas. No toca la red porque el JWK Set se descarga de forma perezosa, y {@code jwt()}
     * inyecta la autenticación ya resuelta, de modo que nunca llega a decodificar nada.
     */
    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextPublishesTheDecoderBuiltByTheConfiguration() {
        assertNotNull(jwtDecoder);
    }

    @Nested
    class WithoutToken {

        @Test
        void readingTheInboxReturnsUnauthorizedWithTheApiErrorContract() throws Exception {
            mockMvc.perform(get(INBOX))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.path").value(INBOX));
        }

        @Test
        void everyResourceReturnsUnauthorizedRatherThanForbidden() throws Exception {
            mockMvc.perform(post(INBOX_READ)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(ACTIVITY)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(ACCESS)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(ADMIN)).andExpect(status().isUnauthorized());
        }

        @Test
        void actuatorHealthAndInfoStayOpen() throws Exception {
            // 404 y no 401: la ruta está permitida, simplemente este slice no monta Actuator.
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
        }

        /**
         * El CORS es de mto-gateway, que quita {@code Origin} antes de llamar. Aquí un preflight no
         * se aprueba: pide token como cualquier otra petición y no vuelve con
         * {@code Access-Control-Allow-Origin}.
         */
        @Test
        void corsIsLeftToTheGateway() throws Exception {
            mockMvc.perform(options(INBOX)
                            .header("Origin", "http://localhost:4200")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        void theRestOfActuatorStaysClosed() throws Exception {
            mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        }

        @Test
        void apiDocsStayClosedWhileExposeApiDocsIsFalse() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class WithInboxRole {

        @Test
        void readingAndMarkingTheInboxSucceed() throws Exception {
            mockMvc.perform(get(INBOX).with(role(SecurityRoles.NOTIFICATION_INBOX))).andExpect(status().isOk());
            mockMvc.perform(post(INBOX_READ).with(role(SecurityRoles.NOTIFICATION_INBOX))).andExpect(status().isOk());
        }

        @Test
        void theInboxDoesNotOpenTheLogTheAccessesOrTheAdministration() throws Exception {
            mockMvc.perform(get(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_INBOX)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403"));
            mockMvc.perform(get(ACCESS).with(role(SecurityRoles.NOTIFICATION_INBOX))).andExpect(status().isForbidden());
            mockMvc.perform(get(ADMIN).with(role(SecurityRoles.NOTIFICATION_INBOX))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithActivityReadRole {

        @Test
        void readingTheLogSucceedsAlsoWithHead() throws Exception {
            mockMvc.perform(get(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ))).andExpect(status().isOk());
            // HEAD lo sirve el mismo handler que GET y revela si un recurso existe, así que va con
            // la misma regla y no cae en el denyAll del final.
            mockMvc.perform(head(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ))).andExpect(status().isOk());
        }

        @Test
        void theLogIsReadOnlyAndDoesNotOpenTheAccesses() throws Exception {
            mockMvc.perform(post(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ))).andExpect(status().isForbidden());
            mockMvc.perform(get(ACCESS).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ))).andExpect(status().isForbidden());
            mockMvc.perform(get(INBOX).with(role(SecurityRoles.NOTIFICATION_ACTIVITY_READ))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithAccessReadRole {

        /**
         * Los accesos llevan usuario e IP: un permiso aparte, que no viene con el registro ni lo
         * incluye.
         */
        @Test
        void readingTheAccessesSucceedsAndOpensNothingElse() throws Exception {
            mockMvc.perform(get(ACCESS).with(role(SecurityRoles.NOTIFICATION_ACCESS_READ))).andExpect(status().isOk());
            mockMvc.perform(head(ACCESS).with(role(SecurityRoles.NOTIFICATION_ACCESS_READ))).andExpect(status().isOk());
            mockMvc.perform(get(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_ACCESS_READ))).andExpect(status().isForbidden());
            mockMvc.perform(get(INBOX).with(role(SecurityRoles.NOTIFICATION_ACCESS_READ))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithAdminRole {

        @Test
        void administrationSucceedsInEveryVerb() throws Exception {
            mockMvc.perform(get(ADMIN).with(role(SecurityRoles.NOTIFICATION_ADMIN))).andExpect(status().isOk());
            mockMvc.perform(post(ADMIN).with(role(SecurityRoles.NOTIFICATION_ADMIN))).andExpect(status().isOk());
        }

        /**
         * Administrar las entregas no es leer la bandeja de nadie ni el registro: los permisos no
         * se implican entre sí, y quien necesite los dos los recibe de su perfil.
         */
        @Test
        void administeringDoesNotGrantTheInboxNorTheLog() throws Exception {
            mockMvc.perform(get(INBOX).with(role(SecurityRoles.NOTIFICATION_ADMIN))).andExpect(status().isForbidden());
            mockMvc.perform(get(ACTIVITY).with(role(SecurityRoles.NOTIFICATION_ADMIN))).andExpect(status().isForbidden());
            mockMvc.perform(get(ACCESS).with(role(SecurityRoles.NOTIFICATION_ADMIN))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class UnderTheApiWithoutARule {

        /**
         * Un recurso nuevo bajo la API sin regla propia no se abre a cualquier autenticado: se
         * deniega, con cualquiera de los cuatro permisos, hasta que declare el suyo.
         */
        @Test
        void everythingElseUnderTheApiIsDeniedWhateverTheRole() throws Exception {
            for (String roleName : new String[]{SecurityRoles.NOTIFICATION_INBOX, SecurityRoles.NOTIFICATION_ACTIVITY_READ,
                    SecurityRoles.NOTIFICATION_ACCESS_READ, SecurityRoles.NOTIFICATION_ADMIN}) {
                mockMvc.perform(get(UNRULED).with(role(roleName)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.errorCode").value("AUTH-403"));
            }
        }
    }

    @Nested
    class WithOperationsRoles {

        @Test
        void metricsReaderCannotTouchTheBusinessApi() throws Exception {
            mockMvc.perform(get(INBOX).with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
            mockMvc.perform(get(ACTIVITY).with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
        }

        /**
         * Leer métricas es observar; reconfigurar algo cambia el estado del sistema. Con un solo
         * rol, cualquiera que pudiera consultar Prometheus podría además dispararlo.
         */
        @Test
        void readingActuatorDoesNotGrantWritingToActuator() throws Exception {
            mockMvc.perform(post("/actuator/loggers/root").with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
            // 404 y no 403: el permiso pasa, el endpoint no existe en este slice.
            mockMvc.perform(post("/actuator/loggers/root").with(role(SecurityRoles.OPS_WRITE))).andExpect(status().isNotFound());
        }
    }

    static RequestPostProcessor role(String... roles) {
        String[] authorities = new String[roles.length];
        for (int index = 0; index < roles.length; index++) {
            authorities[index] = SecurityAuthorityPrefixes.ROLE_PREFIX + roles[index];
        }
        return jwt().authorities(AuthorityUtils.createAuthorityList(authorities));
    }

    /** Una sonda por recurso, con la misma forma de ruta que los controladores reales. */
    @RestController
    @RequestMapping(SecurityConfiguration.API)
    static class ProbeController {

        @GetMapping("/inbox/probe")
        String inbox() {
            return "inbox";
        }

        @PostMapping("/inbox/probe/read")
        String markRead() {
            return "read";
        }

        @GetMapping("/activity/probe")
        String activity() {
            return "activity";
        }

        @PostMapping("/activity/probe")
        String writeActivity() {
            return "never";
        }

        @GetMapping("/access/probe")
        String access() {
            return "access";
        }

        @GetMapping("/admin/probe")
        String admin() {
            return "admin";
        }

        @PostMapping("/admin/probe")
        String administer() {
            return "administered";
        }

        @GetMapping("/other/probe")
        String unruled() {
            return "never";
        }
    }
}
