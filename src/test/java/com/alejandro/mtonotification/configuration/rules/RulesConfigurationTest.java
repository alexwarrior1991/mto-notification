package com.alejandro.mtonotification.configuration.rules;

import com.alejandro.mtonotification.application.exception.RuleDefinitionException;
import com.alejandro.mtonotification.application.service.impl.YamlRuleRepository;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.DeliveryChannels;
import com.alejandro.mtonotification.domain.model.NotificationRule;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El YAML real valida al arrancar y una regla rota impide arrancar: es lo que hace que una errata
 * en un tipo de evento no se descubra el dia que ese evento por fin llega.
 */
class RulesConfigurationTest {

    private static final Resource REAL_RULES = new ClassPathResource("notification-rules.yml");

    @Test
    void theShippedRulesLoadAndNameOnlyKnownTypesAudiencesAndChannels() {
        YamlRuleRepository repository = new YamlRuleRepository(REAL_RULES, Map.of());

        assertFalse(repository.rules().isEmpty());
        Set<String> keys = new HashSet<>();
        for (NotificationRule rule : repository.rules()) {
            assertTrue(keys.add(rule.key()), "clave repetida: " + rule.key());
            rule.channels().forEach(channel -> assertTrue(DeliveryChannels.isKnown(channel), channel));
            assertNotNull(rule.title());
        }
        assertEquals(50, repository.variables().get("large-burst-threshold"));
        assertEquals("mto-users-svc", repository.variables().get("users-service-client-id"));
        assertTrue(repository.source().contains("notification-rules.yml"));
    }

    @Test
    void theShippedRulesCoverTheAlertsOfThisPhase() {
        YamlRuleRepository repository = new YamlRuleRepository(REAL_RULES, Map.of());
        List<String> keys = repository.rules().stream().map(NotificationRule::key).toList();
        assertTrue(keys.containsAll(List.of("access-login-streak", "access-lockout", "configuration-large-burst",
                "configuration-infrastructure-deleted", "users-change-outside-application", "system-delivery-dead",
                "configuration-job-finished", "users-user-created", "users-user-deleted", "users-user-disabled",
                "users-credential-deleted", "users-sessions-revoked", "users-profile-changed", "users-client-roles-changed",
                "users-password-reset", "maintenance-order-urgent", "maintenance-order-assigned", "maintenance-order-closed",
                "maintenance-defect-critical", "maintenance-defect-high", "maintenance-inspection-unsafe", "maintenance-inspection-defect",
                "maintenance-inspection-corrective-order", "maintenance-shift", "maintenance-material-no-stock",
                "maintenance-material-rejected", "maintenance-material-stock-unavailable", "maintenance-asset-disabled",
                "maintenance-preventive-due-soon")));

        NotificationRule job = repository.rules().stream().filter(rule -> rule.key().equals("configuration-job-finished")).findFirst().orElseThrow();
        assertEquals(List.of("USER:#{payload.createdBy}"), job.audiences(), "el trabajo avisa a quien lo lanzo");
        NotificationRule profiles = repository.rules().stream().filter(rule -> rule.key().equals("users-profile-changed")).findFirst().orElseThrow();
        assertTrue(profiles.audiences().contains("USER_ID:#{payload.targetUserId}"), "la persona afectada, por su id: mto-users no siempre sabe su nombre");
        NotificationRule sessions = repository.rules().stream().filter(rule -> rule.key().equals("users-sessions-revoked")).findFirst().orElseThrow();
        assertEquals(Duration.ofMinutes(5), sessions.throttle().window(), "sacar a una persona son tres llamadas y un aviso");

        NotificationRule urgent = repository.rules().stream().filter(rule -> rule.key().equals("maintenance-order-urgent")).findFirst().orElseThrow();
        assertEquals(List.of("inbox", "email"), urgent.channels(), "una orden urgente llega al correo");
        assertTrue(urgent.matcher().matches(ActivityTypes.MAINTENANCE_ORDER_CREATED));
        NotificationRule assigned = repository.rules().stream().filter(rule -> rule.key().equals("maintenance-order-assigned")).findFirst().orElseThrow();
        assertEquals(List.of("USER:#{payload.assignedUser}"), assigned.audiences(), "la orden asignada avisa a la persona asignada");
        assertTrue(assigned.matcher().matches(ActivityTypes.MAINTENANCE_ORDER_REASSIGNED));
        NotificationRule stockUnavailable = repository.rules().stream().filter(rule -> rule.key().equals("maintenance-material-stock-unavailable")).findFirst().orElseThrow();
        assertEquals(Duration.ofHours(1), stockUnavailable.throttle().window(), "el almacen caido avisa una vez por orden y hora");
        assertTrue(stockUnavailable.matcher().matches(ActivityTypes.MAINTENANCE_MATERIAL_IN_DOUBT));

        NotificationRule streak = repository.rules().stream().filter(rule -> rule.key().equals("access-login-streak")).findFirst().orElseThrow();
        assertTrue(streak.matcher().matches(ActivityTypes.ACCESS_LOGIN_STREAK));
        assertEquals(List.of("inbox", "email"), streak.channels());
        assertEquals(Duration.ofMinutes(30), streak.throttle().window());
    }

    @Test
    void propertyVariablesOverrideTheFileOnes() {
        YamlRuleRepository repository = new YamlRuleRepository(REAL_RULES, Map.<String, Object>of("large-burst-threshold", 5));
        assertEquals(5, repository.variables().get("large-burst-threshold"));
    }

    @Test
    void anUnknownEventTypeStopsTheStartup() {
        RuleDefinitionException failure = assertThrows(RuleDefinitionException.class, () -> load("""
                rules:
                  - key: typo
                    event: access.login.typo
                    audiences: [PROFILE:mto-ops]
                    title: t
                """));
        assertTrue(failure.getMessage().contains("typo"));
    }

    @Test
    void aDuplicateKeyAnUnknownChannelOrAnUnknownAudienceKindStopTheStartup() {
        assertThrows(RuleDefinitionException.class, () -> load("""
                rules:
                  - key: same
                    event: access.login
                    audiences: [PROFILE:mto-ops]
                    title: t
                  - key: same
                    event: access.logout
                    audiences: [PROFILE:mto-ops]
                    title: t
                """));
        assertThrows(RuleDefinitionException.class, () -> load("""
                rules:
                  - key: push
                    event: access.login
                    audiences: [PROFILE:mto-ops]
                    channels: [push]
                    title: t
                """));
        assertThrows(RuleDefinitionException.class, () -> load("""
                rules:
                  - key: team
                    event: access.login
                    audiences: ["TEAM:#{payload.team}"]
                    title: t
                """));
        assertThrows(RuleDefinitionException.class, () -> load("""
                rules:
                  - key: window
                    event: access.login
                    audiences: [PROFILE:mto-ops]
                    title: t
                    throttle:
                      key: x
                """));
    }

    @Test
    void anEmptyOrMissingFileIsAnEmptyRuleSetOrAStartupFailure() {
        assertTrue(load("variables: {}\n").rules().isEmpty());
        assertThrows(RuleDefinitionException.class, () -> new YamlRuleRepository(new ClassPathResource("does-not-exist.yml"), Map.of()));
    }

    private static YamlRuleRepository load(String yaml) {
        return new YamlRuleRepository(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8), "inline rules"), Map.of());
    }
}
