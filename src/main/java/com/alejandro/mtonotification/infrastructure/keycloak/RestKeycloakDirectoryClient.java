package com.alejandro.mtonotification.infrastructure.keycloak;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakClient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakRole;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakUser;
import com.alejandro.mtonotification.application.service.KeycloakDirectoryClient;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/** El directorio del realm, de solo lectura, con la cuenta de servicio y dentro del circuito {@code keycloak}. */
public class RestKeycloakDirectoryClient implements KeycloakDirectoryClient {

    private static final ParameterizedTypeReference<List<KeycloakUser>> USERS = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KeycloakRole>> ROLES = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<KeycloakClient>> CLIENTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;

    public RestKeycloakDirectoryClient(RestClient restClient, CircuitBreaker circuitBreaker) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Optional<KeycloakUser> findUserByUsername(String username) {
        List<KeycloakUser> users = KeycloakApiSupport.call(circuitBreaker, "GET /users?username", () -> restClient.get()
                .uri(KeycloakAdminApi.USERS + "?username={username}&exact=true&max=2", username)
                .retrieve()
                .body(USERS));
        return users == null ? Optional.empty() : users.stream().filter(user -> username.equalsIgnoreCase(user.username())).findFirst();
    }

    @Override
    public Optional<KeycloakUser> findUserById(String id) {
        try {
            return Optional.ofNullable(KeycloakApiSupport.call(circuitBreaker, "GET /users/{id}", () -> restClient.get()
                    .uri(KeycloakAdminApi.USERS + "/{id}", id)
                    .retrieve()
                    .body(KeycloakUser.class)));
        } catch (KeycloakApiSupport.NotFound notFound) {
            return Optional.empty();
        }
    }

    @Override
    public List<KeycloakUser> realmRoleMembers(String roleName, int first, int max) {
        try {
            List<KeycloakUser> users = KeycloakApiSupport.call(circuitBreaker, "GET /roles/{role}/users", () -> restClient.get()
                    .uri(KeycloakAdminApi.ROLES + "/{role}/users?first={first}&max={max}", roleName, first, max)
                    .retrieve()
                    .body(USERS));
            return users == null ? List.of() : users;
        } catch (KeycloakApiSupport.NotFound missingRole) {
            return List.of();
        }
    }

    @Override
    public Optional<KeycloakClient> findClient(String clientId) {
        List<KeycloakClient> clients = KeycloakApiSupport.call(circuitBreaker, "GET /clients?clientId", () -> restClient.get()
                .uri(KeycloakAdminApi.CLIENTS + "?clientId={clientId}", clientId)
                .retrieve()
                .body(CLIENTS));
        return clients == null ? Optional.empty() : clients.stream().filter(client -> clientId.equals(client.clientId())).findFirst();
    }

    @Override
    public Optional<KeycloakClient> findClientById(String id) {
        try {
            return Optional.ofNullable(KeycloakApiSupport.call(circuitBreaker, "GET /clients/{id}", () -> restClient.get()
                    .uri(KeycloakAdminApi.CLIENTS + "/{id}", id)
                    .retrieve()
                    .body(KeycloakClient.class)));
        } catch (KeycloakApiSupport.NotFound notFound) {
            return Optional.empty();
        }
    }

    @Override
    public List<KeycloakUser> clientRoleMembers(String clientUuid, String roleName, int first, int max) {
        try {
            List<KeycloakUser> users = KeycloakApiSupport.call(circuitBreaker, "GET /clients/{id}/roles/{role}/users", () -> restClient.get()
                    .uri(KeycloakAdminApi.CLIENTS + "/{id}/roles/{role}/users?first={first}&max={max}", clientUuid, roleName, first, max)
                    .retrieve()
                    .body(USERS));
            return users == null ? List.of() : users;
        } catch (KeycloakApiSupport.NotFound missingRole) {
            return List.of();
        }
    }

    @Override
    public List<KeycloakRole> realmRoles(int first, int max) {
        List<KeycloakRole> roles = KeycloakApiSupport.call(circuitBreaker, "GET /roles", () -> restClient.get()
                .uri(KeycloakAdminApi.ROLES + "?first={first}&max={max}&briefRepresentation=true", first, max)
                .retrieve()
                .body(ROLES));
        return roles == null ? List.of() : roles;
    }

    @Override
    public List<KeycloakRole> clientCompositesOfRealmRole(String realmRoleName, String clientUuid) {
        try {
            List<KeycloakRole> roles = KeycloakApiSupport.call(circuitBreaker, "GET /roles/{role}/composites/clients/{id}", () -> restClient.get()
                    .uri(KeycloakAdminApi.ROLES + "/{role}/composites/clients/{id}", realmRoleName, clientUuid)
                    .retrieve()
                    .body(ROLES));
            return roles == null ? List.of() : roles;
        } catch (KeycloakApiSupport.NotFound missing) {
            return List.of();
        }
    }
}
