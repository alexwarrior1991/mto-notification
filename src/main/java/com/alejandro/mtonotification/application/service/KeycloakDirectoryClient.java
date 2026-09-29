package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakClient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakRole;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakUser;

import java.util.List;
import java.util.Optional;

/** El directorio del realm, de solo lectura: lo justo para poner nombre y direccion a una audiencia. */
public interface KeycloakDirectoryClient {

    Optional<KeycloakUser> findUserByUsername(String username);

    /** Por su id de Keycloak; vacio si ya no existe. */
    Optional<KeycloakUser> findUserById(String id);

    /** Miembros directos de un rol de realm (Keycloak no expande compuestos aqui). */
    List<KeycloakUser> realmRoleMembers(String roleName, int first, int max);

    Optional<KeycloakClient> findClient(String clientId);

    /** Por su id interno (el UUID que Keycloak pone en {@code authDetails.clientId}); vacio si no esta en el realm. */
    Optional<KeycloakClient> findClientById(String id);

    /** Miembros directos de un rol de cliente. */
    List<KeycloakUser> clientRoleMembers(String clientUuid, String roleName, int first, int max);

    List<KeycloakRole> realmRoles(int first, int max);

    /** Los roles de ese cliente que un rol de realm compuesto agrupa. */
    List<KeycloakRole> clientCompositesOfRealmRole(String realmRoleName, String clientUuid);
}
