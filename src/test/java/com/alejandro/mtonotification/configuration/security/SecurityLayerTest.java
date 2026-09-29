package com.alejandro.mtonotification.configuration.security;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprobaciones unitarias de las piezas de seguridad que no necesitan cadena de filtros: la
 * traducción de claims a autoridades, la validación de audiencia, la lectura del usuario actual y
 * las invariantes de configuración. Las reglas por ruta y verbo se prueban en
 * {@link ApiAuthorizationRulesTest}.
 */
class SecurityLayerTest {

    private static final String CLIENT_ID = "mto-notification-api";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void clientRolesBecomeRolePrefixedAuthoritiesNormalizedToUpperCase() {
        AbstractAuthenticationToken authentication = convert(jwt(Map.of(
                JwtClaimNames.RESOURCE_ACCESS, Map.of(CLIENT_ID, Map.of(JwtClaimNames.ROLES, List.of("notification-inbox", "notification activity-read")))
        )));

        assertTrue(authorities(authentication).containsAll(Set.of(
                "ROLE_NOTIFICATION_INBOX", "ROLE_CLIENT_NOTIFICATION_INBOX",
                "ROLE_NOTIFICATION_ACTIVITY_READ", "ROLE_CLIENT_NOTIFICATION_ACTIVITY_READ"
        )));
    }

    /**
     * Si los roles de realm se emitieran también como {@code ROLE_}, crear en Keycloak un rol de
     * realm llamado igual que un permiso bastaría para concederlo: quien administra el realm no es
     * necesariamente quien escribe el código.
     */
    @Test
    void realmRolesNeverProduceThePlainRolePrefixReservedForClientRoles() {
        AbstractAuthenticationToken authentication = convert(jwt(Map.of(
                JwtClaimNames.REALM_ACCESS, Map.of(JwtClaimNames.ROLES, List.of("notification-admin"))
        )));

        Set<String> authorities = authorities(authentication);
        assertTrue(authorities.contains("ROLE_REALM_NOTIFICATION_ADMIN"));
        assertFalse(authorities.contains("ROLE_NOTIFICATION_ADMIN"));
    }

    @Test
    void clientRolesOfOtherClientsAreIgnored() {
        AbstractAuthenticationToken authentication = convert(jwt(Map.of(
                JwtClaimNames.RESOURCE_ACCESS, Map.of("mto-users-api", Map.of(JwtClaimNames.ROLES, List.of("notification-admin")))
        )));

        assertTrue(authorities(authentication).isEmpty());
    }

    @Test
    void scopesBecomeScopePrefixedAuthorities() {
        AbstractAuthenticationToken authentication = convert(jwt(Map.of(JwtClaimNames.SCOPE, "openid profile")));

        assertTrue(authorities(authentication).containsAll(Set.of("SCOPE_openid", "SCOPE_profile")));
    }

    @Test
    void principalNameFallsBackToSubjectWhenTheConfiguredClaimIsMissing() {
        assertEquals("notificacion.lector", convert(jwt(Map.of(JwtClaimNames.PREFERRED_USERNAME, "notificacion.lector"))).getName());
        assertEquals("subject-1", convert(jwt(Map.of())).getName());
    }

    @Test
    void audienceValidatorRejectsTokensIssuedForAnotherApplication() {
        JwtAudienceValidator audienceValidator = new JwtAudienceValidator(CLIENT_ID);

        assertFalse(audienceValidator.validate(jwt(Map.of(JwtClaimNames.AUDIENCE, List.of(CLIENT_ID)))).hasErrors());

        OAuth2TokenValidatorResult rejected = audienceValidator.validate(jwt(Map.of(JwtClaimNames.AUDIENCE, List.of("mto-users-api"))));
        assertTrue(rejected.hasErrors());
    }

    @Test
    void audienceValidatorRefusesToBeBuiltWithoutAnAudience() {
        assertThrows(IllegalArgumentException.class, () -> new JwtAudienceValidator(" "));
    }

    @Test
    void currentUserServiceReadsTheAuthenticatedUserAndIgnoresAnonymousAuthentication() {
        CurrentUserService currentUserService = new CurrentUserService();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt(Map.of(JwtClaimNames.PREFERRED_USERNAME, "notificacion.lector", JwtClaimNames.EMAIL, "lector@mto.local")),
                List.of(new SimpleGrantedAuthority("ROLE_NOTIFICATION_INBOX")),
                "notificacion.lector"
        ));

        assertEquals("notificacion.lector", currentUserService.getUsername().orElseThrow());
        assertEquals("subject-1", currentUserService.getUserId().orElseThrow());
        assertEquals("lector@mto.local", currentUserService.getEmail().orElseThrow());
        assertTrue(currentUserService.hasRole("NOTIFICATION_INBOX"));
        assertTrue(currentUserService.hasRole("ROLE_NOTIFICATION_INBOX"));
        assertFalse(currentUserService.hasRole("NOTIFICATION_ADMIN"));

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertTrue(currentUserService.getUsername().isEmpty());
        assertTrue(currentUserService.getAuthorities().isEmpty());
    }

    @Test
    void currentUserServiceReportsNoUserWhenTheContextIsEmpty() {
        assertTrue(new CurrentUserService().getUsername().isEmpty());
    }

    /**
     * Un {@code required-audience} vacío apagaría la validación de audiencia en tiempo de petición y
     * sin rastro en el log, y la API pasaría a aceptar cualquier token del realm. Que la aplicación
     * no arranque es lo que impide que eso ocurra sin que nadie lo note.
     */
    @Test
    void securityPropertiesRefuseAudienceValidationWithoutAnAudience() {
        assertTrue(validator.validate(properties(true, CLIENT_ID, List.of("http://localhost:4200"))).isEmpty());
        assertTrue(validator.validate(properties(true, " ", List.of("http://localhost:4200"))).stream()
                .anyMatch(violation -> violation.getMessage().contains("required-audience")));
        assertTrue(validator.validate(properties(false, null, List.of("http://localhost:4200"))).isEmpty());
    }

    @Test
    void securityPropertiesRejectAWildcardCorsOrigin() {
        assertTrue(validator.validate(properties(false, null, List.of("*"))).stream()
                .anyMatch(violation -> violation.getMessage().contains("allowed-origins")));
    }

    /**
     * Las claves con las que la bandeja se resuelve al leer: la persona (por nombre y por id, para
     * las fuentes que solo saben el id), cada rol de realm (los perfiles son roles compuestos de
     * realm y el token trae su nombre) y cada rol de cliente.
     */
    @Test
    void currentUserServiceDerivesTheAudienceKeysFromTheToken() {
        CurrentUserService currentUserService = new CurrentUserService();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt(Map.of(JwtClaimNames.PREFERRED_USERNAME, "alice",
                        JwtClaimNames.REALM_ACCESS, Map.of(JwtClaimNames.ROLES, List.of("mto-ops", "offline_access")),
                        JwtClaimNames.RESOURCE_ACCESS, Map.of(
                                "mto-notification-api", Map.of(JwtClaimNames.ROLES, List.of("notification-inbox", "notification-admin")),
                                "account", Map.of(JwtClaimNames.ROLES, List.of("view-profile")),
                                "broken", "not a map"))),
                List.of(new SimpleGrantedAuthority("ROLE_NOTIFICATION_INBOX")),
                "alice"
        ));

        assertEquals(List.of("USER:alice", "USER_ID:subject-1", "PROFILE:mto-ops", "PROFILE:offline_access",
                        "CLIENT_ROLE:mto-notification-api:notification-inbox", "CLIENT_ROLE:mto-notification-api:notification-admin",
                        "CLIENT_ROLE:account:view-profile"),
                currentUserService.getAudienceKeys());

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt(Map.of(JwtClaimNames.PREFERRED_USERNAME, "bob")), List.of(), "bob"));
        assertEquals(List.of("USER:bob", "USER_ID:subject-1"), currentUserService.getAudienceKeys(), "sin roles, solo la persona");

        SecurityContextHolder.clearContext();
        assertTrue(currentUserService.getAudienceKeys().isEmpty());
    }

    private static SecurityProperties properties(boolean audienceValidationEnabled, String requiredAudience, List<String> allowedOrigins) {
        return new SecurityProperties(
                CLIENT_ID,
                JwtClaimNames.PREFERRED_USERNAME,
                audienceValidationEnabled,
                requiredAudience,
                false,
                new SecurityProperties.Cors(allowedOrigins, List.of("GET"), List.of("Authorization"), List.of(), false, 3600)
        );
    }

    private static AbstractAuthenticationToken convert(Jwt jwt) {
        return new KeycloakJwtAuthenticationConverter(properties(false, null, List.of("http://localhost:4200"))).convert(jwt);
    }

    private static Set<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject-1")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
