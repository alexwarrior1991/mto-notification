package com.alejandro.mtonotification.configuration.security;

import com.alejandro.mtonotification.infrastructure.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La contraparte de {@link ApiAuthorizationRulesTest}, que prueba el caso cerrado. Aquí se comprueba
 * que la property abre de verdad la documentación: si dejara de hacerlo, acabaría reabriéndose a
 * base de {@code permitAll} en la cadena, que es lo que la property existe para evitar.
 */
@WebMvcTest(controllers = ApiAuthorizationRulesTest.ProbeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        ApiAuthorizationRulesTest.ProbeController.class})
@TestPropertySource(properties = {
        "app.security.client-id=mto-notification-api",
        "app.security.principal-claim=preferred_username",
        "app.security.audience-validation-enabled=false",
        "app.security.expose-api-docs=true",
        "app.security.cors.allowed-origins=http://localhost:4200",
        "app.security.cors.allowed-methods=GET",
        "app.security.cors.allowed-headers=Authorization",
        "app.security.cors.allow-credentials=false",
        "app.security.cors.max-age=3600",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8082/realms/mto"
})
class ApiDocsExposureTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentationDoesNotRequireATokenWhenExposeApiDocsIsTrue() throws Exception {
        // 404 y no 401: la ruta está permitida; springdoc no forma parte de este slice.
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }

    @Test
    void openingTheDocumentationDoesNotOpenTheApi() throws Exception {
        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX)).andExpect(status().isUnauthorized());
    }
}
