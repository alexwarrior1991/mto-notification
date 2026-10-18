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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                "maintenance-preventive-due-soon", "stock-material-below-minimum", "stock-reservation-touched-by-someone-else",
                "stock-large-negative-adjustment", "field-possession-opened", "field-possession-closed", "field-evacuation-issued",
                "field-evacuation-refused", "field-evacuation-complete", "field-evacuation-unacknowledged", "field-all-clear",
                "maintenance-shift-team-leader", "resources-team-changed", "resources-team-deactivated", "resources-vehicle-unavailable",
                "resources-qualification-expiring", "resources-qualification-expired", "resources-vehicle-document-expiring",
                "resources-vehicle-document-expired", "resources-calibration-expiring", "resources-calibration-expired")));

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
        NotificationRule shift = repository.rules().stream().filter(rule -> rule.key().equals("maintenance-shift")).findFirst().orElseThrow();
        assertTrue(shift.matcher().matches(ActivityTypes.MAINTENANCE_SHIFT_CANCELLED), "un turno cancelado avisa como uno iniciado o cerrado");
        assertTrue(shift.matcher().matches(ActivityTypes.MAINTENANCE_SHIFT_STARTED) && shift.matcher().matches(ActivityTypes.MAINTENANCE_SHIFT_CLOSED));

        NotificationRule leader = repository.rules().stream().filter(rule -> rule.key().equals("maintenance-shift-team-leader")).findFirst().orElseThrow();
        assertEquals(List.of("USER:#{payload.teamLeaderUsername}"), leader.audiences(), "el jefe de la dotacion del turno");
        assertTrue(leader.matcher().matches(ActivityTypes.MAINTENANCE_SHIFT_CANCELLED));
        NotificationRule teamChanged = repository.rules().stream().filter(rule -> rule.key().equals("resources-team-changed")).findFirst().orElseThrow();
        assertEquals(List.of("USER:#{payload.leader?.username}"), teamChanged.audiences(), "el jefe del equipo, dentro del evento");
        assertTrue(teamChanged.matcher().matches(ActivityTypes.RESOURCES_TEAM_DEACTIVATED));
        NotificationRule vehicleUnavailable = repository.rules().stream().filter(rule -> rule.key().equals("resources-vehicle-unavailable")).findFirst().orElseThrow();
        assertTrue(vehicleUnavailable.audiences().contains("PROFILE:mto-resources-manager"));
        assertTrue(vehicleUnavailable.matcher().matches(ActivityTypes.RESOURCES_VEHICLE_DEACTIVATED), "retirar uno disponible es dejarlo sin disponibilidad");

        List<String> resourcesManagers = List.of("PROFILE:mto-resources-manager", "PROFILE:mto-resources-admin");
        List<String> fleetManagers = List.of("PROFILE:mto-resources-manager", "PROFILE:mto-resources-admin", "PROFILE:mto-maintenance-manager");
        Map<String, String> expiries = Map.of(
                "resources-qualification-expiring", ActivityTypes.RESOURCES_QUALIFICATION_EXPIRING,
                "resources-qualification-expired", ActivityTypes.RESOURCES_QUALIFICATION_EXPIRED,
                "resources-vehicle-document-expiring", ActivityTypes.RESOURCES_VEHICLE_DOCUMENT_EXPIRING,
                "resources-vehicle-document-expired", ActivityTypes.RESOURCES_VEHICLE_DOCUMENT_EXPIRED,
                "resources-calibration-expiring", ActivityTypes.RESOURCES_CALIBRATION_EXPIRING,
                "resources-calibration-expired", ActivityTypes.RESOURCES_CALIBRATION_EXPIRED);
        expiries.forEach((key, type) -> {
            NotificationRule expiry = repository.rules().stream().filter(rule -> rule.key().equals(key)).findFirst().orElseThrow();
            assertTrue(expiry.matcher().matches(type), key);
            assertEquals(type.endsWith(".expired") ? List.of("inbox", "email") : List.of("inbox"), expiry.channels(),
                    key + ": lo caducado llega tambien al correo, lo que va a caducar solo a la bandeja");
            assertNull(expiry.severity(), key + ": la gravedad es la de la linea (WARNING o CRITICAL)");
            if (key.startsWith("resources-qualification")) {
                assertEquals(List.of("USER:#{payload.username}", "USER:#{payload.leader?.username}", resourcesManagers.get(0), resourcesManagers.get(1)),
                        expiry.audiences(), key + ": la persona, el jefe de su equipo y los responsables de recursos");
                assertEquals("/recursos/personal/#{payload.personId}", expiry.link());
            } else {
                assertEquals(fleetManagers, expiry.audiences(), key + ": los responsables de recursos y el de mantenimiento");
            }
        });
        assertEquals("/recursos/flota/#{payload.vehicleId}", repository.rules().stream()
                .filter(rule -> rule.key().equals("resources-vehicle-document-expired")).findFirst().orElseThrow().link());
        assertEquals("/recursos/instrumentos", repository.rules().stream()
                .filter(rule -> rule.key().equals("resources-calibration-expiring")).findFirst().orElseThrow().link());

        NotificationRule belowMinimum = repository.rules().stream().filter(rule -> rule.key().equals("stock-material-below-minimum")).findFirst().orElseThrow();
        assertEquals(Duration.ofHours(24), belowMinimum.throttle().window(), "un material bajo minimo avisa una vez al dia");
        assertEquals(List.of("inbox", "email"), belowMinimum.channels());
        NotificationRule touched = repository.rules().stream().filter(rule -> rule.key().equals("stock-reservation-touched-by-someone-else")).findFirst().orElseThrow();
        assertEquals(List.of("PROFILE:mto-maintenance-manager"), touched.audiences(), "la reserva de mantenimiento tocada por otro avisa a mantenimiento");
        assertTrue(touched.matcher().matches(ActivityTypes.STOCK_RESERVATION_RELEASED));
        assertEquals(100, repository.variables().get("large-adjustment-threshold"));
        assertEquals("service-account-mto-maintenance-svc", repository.variables().get("maintenance-service-account"));

        NotificationRule evacuation = repository.rules().stream().filter(rule -> rule.key().equals("field-evacuation-issued")).findFirst().orElseThrow();
        assertEquals(List.of("inbox", "email"), evacuation.channels(), "un desalojo de la via llega al correo");
        assertTrue(evacuation.matcher().matches(ActivityTypes.FIELD_POSSESSION_EVACUATION_ISSUED));
        assertTrue(evacuation.audiences().contains("PROFILE:mto-field-supervisor"), "el responsable de campo, que tiene bandeja desde la fase 5 de mto-field");
        assertTrue(evacuation.audiences().contains("PROFILE:mto-maintenance-manager"));
        NotificationRule refused = repository.rules().stream().filter(rule -> rule.key().equals("field-evacuation-refused")).findFirst().orElseThrow();
        assertTrue(refused.matcher().matches(ActivityTypes.FIELD_POSSESSION_EVACUATION_ACKNOWLEDGED), "el acuse que dice que no es un acuse");
        assertEquals(List.of("inbox", "email"), refused.channels());
        NotificationRule allClear = repository.rules().stream().filter(rule -> rule.key().equals("field-all-clear")).findFirst().orElseThrow();
        assertTrue(allClear.matcher().matches(ActivityTypes.FIELD_POSSESSION_CLEAR_OF_TRACK));
        assertEquals(List.of("inbox"), allClear.channels(), "la via libre es una buena noticia: solo la bandeja");

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
