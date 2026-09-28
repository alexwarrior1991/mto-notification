package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.keycloak.KeycloakClient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakRole;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakUser;

import java.util.List;
import java.util.Optional;

/** El directorio del realm, de solo lectura: lo justo para poner nombre y direccion a una audiencia. */
public interface KeycloakDirectoryClient {

    Optional<KeycloakUser> findUserByUsername(String username);

    /** Miembros directos de un rol de realm (Keycloak no expande compuestos aqui). */
    List<KeycloakUser> realmRoleMembers(String roleName, int first, int max);

    Optional<KeycloakClient> findClient(String clientId);

    /** Miembros directos de un rol de cliente. */
    List<KeycloakUser> clientRoleMembers(String clientUuid, String roleName, int first, int max);

    List<KeycloakRole> realmRoles(int first, int max);

    /** Los roles de ese cliente que un rol de realm compuesto agrupa. */
    List<KeycloakRole> clientCompositesOfRealmRole(String realmRoleName, String clientUuid);
}
