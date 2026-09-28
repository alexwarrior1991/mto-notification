package com.alejandro.mtonotification.infrastructure.keycloak;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakUser;
import com.alejandro.mtonotification.application.exception.DirectoryUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Los dos clientes contra un servidor simulado: las rutas y los parametros de paginacion, el JSON
 * crudo que se conserva por evento, y como se traducen un 401 (la cuenta de servicio sin roles),
 * un 404 (no existe) y un fallo de red.
 */
class KeycloakEventsClientTest {

    private static final String BASE = "http://keycloak:8080/admin/realms/mto";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient restClient = builder.build();
    private final CircuitBreaker passThrough = new PassThroughCircuitBreaker();
    private final RestKeycloakEventsClient eventsClient = new RestKeycloakEventsClient(restClient, JsonMapper.builder().build(), passThrough);
    private final RestKeycloakDirectoryClient directoryClient = new RestKeycloakDirectoryClient(restClient, passThrough);

    @Test
    void loginEventsAreReadNewestFirstWithTheirRawJsonKept() {
        server.expect(requestTo(BASE + "/events?first=0&max=2")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"time":1700000002000,"type":"LOGIN","realmId":"r","clientId":"mto-frontend","userId":"u2","ipAddress":"10.0.0.2","details":{"username":"bob","auth_method":"openid-connect"}},
                         {"time":1700000001000,"type":"LOGIN_ERROR","realmId":"r","clientId":"mto-frontend","userId":"u1","ipAddress":"10.0.0.1","error":"invalid_user_credentials","details":{"username":"alice"}}]
                        """, MediaType.APPLICATION_JSON));

        List<KeycloakEvent> events = eventsClient.loginEvents(0, 2);

        assertEquals(2, events.size());
        assertEquals("LOGIN", events.getFirst().type());
        assertEquals("bob", events.getFirst().detail("username"));
        assertEquals("invalid_user_credentials", events.get(1).error());
        assertTrue(events.get(1).raw().contains("\"userId\":\"u1\""), "el JSON del evento tal como llego");
        server.verify();
    }

    @Test
    void adminEventsCarryTheirAuthDetailsAndRepresentation() {
        server.expect(requestTo(BASE + "/admin-events?first=0&max=100"))
                .andRespond(withSuccess("""
                        [{"time":1700000003000,"realmId":"r","authDetails":{"realmId":"r","clientId":"mto-users-svc","userId":"svc","ipAddress":"10.0.0.9"},
                          "operationType":"UPDATE","resourceType":"USER","resourcePath":"users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21","representation":"{\\"enabled\\":false}"}]
                        """, MediaType.APPLICATION_JSON));

        List<KeycloakAdminEvent> events = eventsClient.adminEvents(0, 100);

        assertEquals(1, events.size());
        assertEquals("mto-users-svc", events.getFirst().clientId());
        assertEquals("users/8d1f6d33-8c9e-4c0d-9f5b-2b1c0f3a4e21", events.getFirst().resourcePath());
        assertEquals("{\"enabled\":false}", events.getFirst().representation());
    }

    @Test
    void anEmptyBodyIsAnEmptyPage() {
        server.expect(requestTo(BASE + "/events?first=0&max=100")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertTrue(eventsClient.loginEvents(0, 100).isEmpty());
    }

    @Test
    void aRefusedServiceAccountIsReportedAsSuchAndNotAsANetworkFailure() {
        server.expect(requestTo(BASE + "/events?first=0&max=100")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        DirectoryUnavailableException failure = assertThrows(DirectoryUnavailableException.class, () -> eventsClient.loginEvents(0, 100));
        assertTrue(failure.getMessage().contains("realm-management"));
    }

    @Test
    void theDirectoryFindsAUserByExactUsername() {
        server.expect(requestTo(BASE + "/users?username=alice&exact=true&max=2"))
                .andRespond(withSuccess("""
                        [{"id":"u1","username":"alice","email":"alice@mto.local","enabled":true,"firstName":"Alice"}]
                        """, MediaType.APPLICATION_JSON));
        Optional<KeycloakUser> user = directoryClient.findUserByUsername("alice");
        assertEquals("alice@mto.local", user.orElseThrow().email());
        assertTrue(user.orElseThrow().isEnabled());
    }

    @Test
    void aMissingRoleHasNoMembersAndAServerErrorIsUnavailable() {
        server.expect(requestTo(BASE + "/roles/mto-nadie/users?first=0&max=200")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/clients?clientId=mto-stock-api")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertTrue(directoryClient.realmRoleMembers("mto-nadie", 0, 200).isEmpty());
        assertThrows(DirectoryUnavailableException.class, () -> directoryClient.findClient("mto-stock-api"));
    }

    @Test
    void roleNamesWithSpacesAreEncodedInThePath() {
        server.expect(requestTo(BASE + "/roles/con%20espacio/composites/clients/c-uuid")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertTrue(directoryClient.clientCompositesOfRealmRole("con espacio", "c-uuid").isEmpty());
    }

    /** Un circuito que solo ejecuta: lo que se prueba aqui es la traduccion de errores, no Resilience4j. */
    private static final class PassThroughCircuitBreaker implements CircuitBreaker {
        @Override
        public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
            try {
                return toRun.get();
            } catch (RuntimeException failure) {
                return fallback.apply(failure);
            }
        }
    }
}
