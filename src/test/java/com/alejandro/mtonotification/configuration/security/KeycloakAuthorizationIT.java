package com.alejandro.mtonotification.configuration.security;

import com.alejandro.mtonotification.infrastructure.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Autorización de extremo a extremo contra un Keycloak real: los tokens los emite el servidor, con
 * su firma y sus claims, y la aplicación los valida y los traduce a permisos sin ningún doble por
 * medio.
 *
 * <p>Se complementa con {@link ApiAuthorizationRulesTest}, que cubre la misma cadena con la
 * autenticación ya inyectada y sin contenedores. Lo que añade este test es lo que aquel no puede
 * comprobar: que el <b>realm</b> esté bien montado. En concreto, que el <i>audience mapper</i>
 * exista —sin él Keycloak no incluye esta API en el {@code aud} y ningún token validaría—, que los
 * permisos vivan como roles de cliente, y que un perfil compuesto de realm llegue expandido dentro
 * de {@code resource_access}. Esas tres cosas son configuración del servidor, no código, y por eso
 * solo se ven aquí.</p>
 *
 * <p>El realm de {@code src/test/resources/keycloak/mto-notification-test-realm.json} tiene la misma
 * forma que el de {@code keycloak/}, así que sirve además de ejemplo ejecutable de cómo hay que
 * configurarlo.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@WebMvcTest(controllers = ApiAuthorizationRulesTest.ProbeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        ApiAuthorizationRulesTest.ProbeController.class})
class KeycloakAuthorizationIT {

    private static final String REALM = "mto";
    private static final String API_CLIENT_ID = "mto-notification-api";
    private static final String CLIENT_WITH_AUDIENCE = "mto-test-frontend";
    private static final String CLIENT_WITHOUT_AUDIENCE = "mto-test-ajeno";

    private static final int HTTP_PORT = 8080;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * {@code --import-realm} deja el realm listo al arrancar, así que no hace falta programar la
     * consola de administración desde el test. La sonda espera al documento de descubrimiento y no
     * a que el puerto acepte conexiones: Keycloak escucha bastante antes de servir el realm.
     */
    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.1"))
                    .withExposedPorts(HTTP_PORT)
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                    // El nombre dentro del contenedor tiene que ser <realm>-realm.json: un fichero cuyo
                    // nombre contiene "-realm.json" va por DirImportProvider, que saca el nombre del
                    // realm del nombre del fichero y lo vincula a la sesion en la ultima transaccion
                    // (la que inicializa las cuentas de servicio). Con "mto-notification-test-realm.json"
                    // buscaba un realm "mto-notification-test", no lo encontraba y la importacion moria
                    // con "Session not bound to a realm" al enlazar el usuario de mto-notification-svc.
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("keycloak/mto-notification-test-realm.json"),
                            "/opt/keycloak/data/import/" + REALM + "-realm.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                            .forPort(HTTP_PORT)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(5)));

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        // El emisor tiene que coincidir con el 'iss' que Keycloak escribe en el token, y Keycloak lo
        // deriva de la URL por la que se le pide: la misma que se usa aquí para pedirlo.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", KeycloakAuthorizationIT::realmUrl);

        registry.add("app.security.client-id", () -> API_CLIENT_ID);
        registry.add("app.security.principal-claim", () -> "preferred_username");
        // Activada a propósito: es la mitad del realm que este test existe para comprobar.
        registry.add("app.security.audience-validation-enabled", () -> "true");
        registry.add("app.security.required-audience", () -> API_CLIENT_ID);
        registry.add("app.security.expose-api-docs", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aClientRoleOpensItsResourceAndOnlyItsResource() throws Exception {
        String token = tokenFor("lector", "lector", CLIENT_WITH_AUDIENCE);

        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(ApiAuthorizationRulesTest.ACTIVITY).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /**
     * Keycloak expande los roles compuestos al emitir el token. Es lo que sostiene el modelo: los
     * perfiles se diseñan en el realm y el código solo comprueba permisos.
     */
    @Test
    void aCompositeRealmProfileArrivesWithItsClientRolesExpanded() throws Exception {
        String token = tokenFor("auditor", "auditor", CLIENT_WITH_AUDIENCE);

        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(ApiAuthorizationRulesTest.ACCESS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // El perfil de auditor no incluye notification-admin.
        mockMvc.perform(get(ApiAuthorizationRulesTest.ADMIN).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAdminProfileCarriesEveryPermission() throws Exception {
        String token = tokenFor("responsable", "responsable", CLIENT_WITH_AUDIENCE);

        mockMvc.perform(get(ApiAuthorizationRulesTest.ADMIN).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(ApiAuthorizationRulesTest.ACCESS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * Contra un servidor real: el usuario tiene un rol <em>de realm</em> llamado
     * {@code notification-admin}, igual que el permiso de cliente, y además el permiso de cliente
     * {@code notification-inbox}. El rol de realm homónimo no debe abrir la administración: quien
     * administra el realm no es necesariamente quien escribe el código, y si lo abriera bastaría con
     * crear allí un rol con el nombre adecuado. Es lo que sostiene que los roles de realm se emitan
     * solo como {@code ROLE_REALM_*}.
     */
    @Test
    void aRealmRoleNamedLikeAPermissionDoesNotGrantThatPermission() throws Exception {
        String token = tokenFor("impostor", "impostor", CLIENT_WITH_AUDIENCE);

        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get(ApiAuthorizationRulesTest.ADMIN).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /**
     * Un token del mismo realm, bien firmado y con el mismo emisor, que no nombra a esta API en su
     * {@code aud}.
     *
     * <p>El usuario es deliberadamente uno <b>sin roles</b>. Keycloak añade por su cuenta al
     * {@code aud} los clientes en los que el usuario tiene roles —lo hace el mapper <i>audience
     * resolve</i> del scope {@code roles}, que va de serie—, así que un usuario con permisos habría
     * recibido la audiencia igual, sin mapper y sin que el test lo notara.</p>
     */
    @Test
    void aTokenFromAnotherClientOfTheSameRealmIsRejectedByAudience() throws Exception {
        String token = tokenFor("ajeno", "ajeno", CLIENT_WITHOUT_AUDIENCE);

        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * La contraparte del anterior, con el mismo usuario sin roles y el cliente que sí lleva el
     * <i>audience mapper</i>. Que uno dé 401 y el otro 403 aísla al mapper: en el segundo caso la
     * audiencia se acepta y lo único que falta son permisos.
     */
    @Test
    void theAudienceMapperGivesTheAudienceEvenToAUserWithNoRoles() throws Exception {
        String token = tokenFor("ajeno", "ajeno", CLIENT_WITH_AUDIENCE);

        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutATokenTheAnswerIsStillUnauthorizedWithItsBearerChallenge() throws Exception {
        mockMvc.perform(get(ApiAuthorizationRulesTest.INBOX))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> {
                    String challenge = result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE);
                    assertNotNull(challenge);
                    assertTrue(challenge.startsWith("Bearer"), challenge);
                });
    }

    // --- obtención de tokens reales ---

    private static String realmUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(HTTP_PORT) + "/realms/" + REALM;
    }

    /**
     * Se usa el <i>grant</i> de acceso directo por comodidad del test: es la forma más corta de
     * conseguir un token de un usuario concreto sin simular un navegador. No es la que debe usar la
     * aplicación.
     */
    private String tokenFor(String username, String password, String clientId) throws Exception {
        String form = "grant_type=password"
                + "&client_id=" + encode(clientId)
                + "&username=" + encode(username)
                + "&password=" + encode(password)
                + "&scope=openid";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(realmUrl() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(),
                () -> "Keycloak did not issue a token for '%s' with client '%s': %s"
                        .formatted(username, clientId, response.body()));

        return JSON.readValue(response.body(), Map.class).get("access_token").toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
