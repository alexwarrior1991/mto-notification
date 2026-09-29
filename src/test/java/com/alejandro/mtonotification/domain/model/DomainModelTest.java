package com.alejandro.mtonotification.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Las reglas sin framework: tipos, audiencias, casado, lista blanca, huellas. */
class DomainModelTest {

    // --- tipos ---

    @Test
    void everyCatalogueTypeIsWellFormedAndCarriesItsCategory() {
        assertFalse(ActivityTypes.catalogue().isEmpty());
        ActivityTypes.catalogue().forEach((type, category) -> {
            assertTrue(ActivityTypes.isWellFormed(type), type);
            assertEquals(category, ActivityCategory.ofType(type), type);
        });
        assertEquals(ActivityCategory.ACCESS, ActivityCategory.ofType(ActivityTypes.ACCESS_LOGIN_STREAK));
        assertEquals(ActivityCategory.CONFIGURATION, ActivityCategory.ofType(ActivityTypes.masterDataType("profile", "updated")));
        assertTrue(ActivityTypes.isKnown("configuration.section-insulator.deleted"));
    }

    @Test
    void aTypeWithoutACategoryOrWithUpperCaseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ActivityTypes.requireWellFormed("Access.Login"));
        assertThrows(IllegalArgumentException.class, () -> ActivityTypes.requireWellFormed("unknown.thing"));
        assertThrows(IllegalArgumentException.class, () -> ActivityTypes.requireWellFormed("access"));
        assertTrue(ActivityTypes.categoryOf("nope").isEmpty());
    }

    // --- audiencias ---

    @Test
    void audienceKeysRoundTripThroughTheirSerializedForm() {
        assertEquals("USER:alice", Audience.user("alice").toKey());
        assertEquals("PROFILE:mto-admin", Audience.profile("mto-admin").toKey());
        assertEquals("CLIENT_ROLE:mto-stock-api:stock-read", Audience.clientRole("mto-stock-api", "stock-read").toKey());

        assertEquals(Optional.of(Audience.user("alice")), Audience.parse("USER:alice"));
        assertEquals(Optional.of(Audience.clientRole("mto-stock-api", "stock-read")), Audience.parse("client-role:mto-stock-api:stock-read"));
        assertTrue(Audience.parse("TEAM:north").isEmpty(), "una clase que aun no existe no abre nada");
        assertTrue(Audience.parse("USER:").isEmpty());
        assertTrue(Audience.parse("nonsense").isEmpty());
        assertTrue(Audience.parse("CLIENT_ROLE:sin-dos-puntos").isEmpty());
    }

    @Test
    void aClientRoleAudienceNeedsClientAndRole() {
        assertThrows(IllegalArgumentException.class, () -> new Audience(AudienceKind.CLIENT_ROLE, "stock-read"));
        assertThrows(IllegalArgumentException.class, () -> Audience.user(" "));
    }

    // --- casado ---

    @Test
    void matcherAcceptsExactTypesListsAndTrailingWildcards() {
        EventTypeMatcher exact = EventTypeMatcher.of(List.of(ActivityTypes.ACCESS_LOGIN_STREAK));
        assertTrue(exact.matches(ActivityTypes.ACCESS_LOGIN_STREAK));
        assertFalse(exact.matches(ActivityTypes.ACCESS_LOGIN));

        EventTypeMatcher list = EventTypeMatcher.of(List.of(ActivityTypes.ACCESS_LOGIN, ActivityTypes.ACCESS_LOGOUT));
        assertTrue(list.matches(ActivityTypes.ACCESS_LOGOUT));

        EventTypeMatcher glob = EventTypeMatcher.of(List.of("maintenance.order.*"));
        assertTrue(glob.matches(ActivityTypes.MAINTENANCE_ORDER_CREATED));
        assertFalse(glob.matches(ActivityTypes.MAINTENANCE_DEFECT_CREATED));

        EventTypeMatcher category = EventTypeMatcher.of(List.of("configuration.*"));
        assertTrue(category.matches("configuration.profile.updated"));
        assertEquals(List.of("configuration.*"), category.describe());
    }

    @Test
    void matcherRejectsTypesOutsideTheCatalogueAndEmptyPatterns() {
        assertThrows(IllegalArgumentException.class, () -> EventTypeMatcher.of(List.of("access.login.typo")));
        assertThrows(IllegalArgumentException.class, () -> EventTypeMatcher.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> EventTypeMatcher.of(List.of("nowhere.*")));
    }

    // --- reglas ---

    @Test
    void aRuleValidatesItsKeyAudiencesAndChannels() {
        NotificationRule rule = new NotificationRule("access-streak", EventTypeMatcher.of(List.of(ActivityTypes.ACCESS_LOGIN_STREAK)),
                null, ActivitySeverity.CRITICAL, List.of("PROFILE:mto-ops", "USER:#{event.actorUsername}"), List.of("Inbox", "EMAIL"),
                "t", null, null, new NotificationRule.Throttle(Duration.ofMinutes(5), " "));
        assertEquals(List.of("inbox", "email"), rule.channels());
        assertNull(rule.throttle().key());

        assertThrows(IllegalArgumentException.class, () -> new NotificationRule("Bad Key", rule.matcher(), null, null,
                List.of("PROFILE:x"), List.of(), "t", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NotificationRule("k", rule.matcher(), null, null,
                List.of("TEAM:x"), List.of(), "t", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NotificationRule("k", rule.matcher(), null, null,
                List.of("PROFILE:x"), List.of("push"), "t", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NotificationRule("k", rule.matcher(), null, null,
                List.of(), List.of(), "t", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NotificationRule.Throttle(Duration.ZERO, null));
    }

    // --- lista blanca ---

    @Test
    void sanitizerDropsAnythingThatLooksLikeACredentialAtAnyDepth() {
        Map<String, Object> dirty = Map.of(
                "username", "alice",
                "password", "hunter2",
                "resetToken", "abc",
                "nested", Map.of("clientSecret", "s3cr3t", "reason", "ok"),
                "list", List.of(Map.of("otp", "123456", "kept", 1)));
        Map<String, Object> clean = PayloadSanitizer.sanitize(dirty);
        assertEquals("alice", clean.get("username"));
        assertFalse(clean.containsKey("password"));
        assertFalse(clean.containsKey("resetToken"));
        @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) clean.get("nested");
        assertEquals(Map.of("reason", "ok"), nested);
        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) clean.get("list");
        assertEquals(List.of(Map.of("kept", 1)), list);
        assertTrue(PayloadSanitizer.isForbiddenKey("Authorization"));
    }

    @Test
    void sanitizerTruncatesLongStringsAndCopiesNothingElse() {
        String longText = "x".repeat(PayloadSanitizer.MAX_STRING_LENGTH + 10);
        Map<String, Object> clean = PayloadSanitizer.sanitize(Map.of("text", longText, "flag", true, "n", 3));
        assertEquals(PayloadSanitizer.MAX_STRING_LENGTH, ((String) clean.get("text")).length());
        assertEquals(true, clean.get("flag"));
        assertEquals(3, clean.get("n"));
        assertEquals(Map.of(), PayloadSanitizer.sanitize(null));
    }

    // --- el borrador ---

    @Test
    void draftDropsTheIpOutsideAccessAndDerivesTheCategory() {
        ActivityEventDraft access = ActivityEventDraft.builder()
                .source("keycloak-login", "abc").type(ActivityTypes.ACCESS_LOGIN).occurredAt(Instant.now())
                .ipAddress("10.0.0.1").payload(Map.of("password", "x", "username", "alice")).build();
        assertEquals(ActivityCategory.ACCESS, access.category());
        assertEquals("10.0.0.1", access.ipAddress());
        assertEquals(Map.of("username", "alice"), access.payload());

        ActivityEventDraft users = ActivityEventDraft.builder()
                .source("keycloak-admin", "abc").type(ActivityTypes.USERS_ADMIN_USER_UPDATED).occurredAt(Instant.now())
                .ipAddress("10.0.0.1").build();
        assertNull(users.ipAddress(), "la IP solo vive en ACCESS");
        assertEquals(ActorKind.SYSTEM, users.actor().kind());
        assertEquals(1, users.eventCount());
    }

    @Test
    void draftNeverTruncatesItsIdentityAndRejectsAZeroCount() {
        ActivityEventDraft.Builder tooLong = ActivityEventDraft.builder()
                .source("s", "x".repeat(ActivityEventDraft.MAX_SOURCE_EVENT_ID_LENGTH + 1)).type(ActivityTypes.ACCESS_LOGIN).occurredAt(Instant.now());
        assertThrows(IllegalArgumentException.class, tooLong::build);
        assertThrows(IllegalArgumentException.class, () -> ActivityEventDraft.builder()
                .source("s", "1").type(ActivityTypes.ACCESS_LOGIN).occurredAt(Instant.now()).eventCount(0).build());
    }

    // --- actores y huellas ---

    @Test
    void actorsAreClassifiedByTheirUsername() {
        assertEquals(ActorKind.SERVICE, Actor.ofUsername("service-account-mto-users-svc", "id").kind());
        assertEquals(ActorKind.PERSON, Actor.ofUsername("alice", null).kind());
        assertEquals(ActorKind.SYSTEM, Actor.ofUsername(" ", null).kind());
        assertEquals("service-account-mto-users-svc", Actor.service("mto-users-svc", null).username());
    }

    @Test
    void fingerprintsAreStableWhateverTheOrderOfTheDetails() {
        String first = Fingerprints.sha256(Fingerprints.canonical(1L, "LOGIN", Fingerprints.canonical(Map.of("a", "1", "b", "2"))));
        String second = Fingerprints.sha256(Fingerprints.canonical(1L, "LOGIN", Fingerprints.canonical(Map.of("b", "2", "a", "1"))));
        String other = Fingerprints.sha256(Fingerprints.canonical(1L, "LOGIN", Fingerprints.canonical(Map.of("a", "1", "b", "3"))));
        assertEquals(first, second);
        assertFalse(first.equals(other));
        assertEquals(64, first.length());
        assertEquals("a||c", Fingerprints.canonical("a", null, "c"));
    }
}
