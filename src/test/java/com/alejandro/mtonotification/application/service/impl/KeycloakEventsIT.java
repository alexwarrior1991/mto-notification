package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakAdminEvent;
import com.alejandro.mtonotification.application.dto.keycloak.KeycloakEvent;
import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.AudienceResolver;
import com.alejandro.mtonotification.application.service.InboxMessageService;
import com.alejandro.mtonotification.application.service.KeycloakEventsClient;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakProperties;
import com.alejandro.mtonotification.application.service.KeycloakEventsPoller;
import com.alejandro.mtonotification.application.service.SourceCursorService;
import com.alejandro.mtonotification.domain.model.ActivityTypes;
import com.alejandro.mtonotification.domain.model.ActorKind;
import com.alejandro.mtonotification.domain.model.Audience;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryScope;
import com.alejandro.mtonotification.infrastructure.persistence.entity.DeliveryStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;
import com.alejandro.mtonotification.infrastructure.persistence.entity.NotificationAudience;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceCursor;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.DeliveryRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.InboxMessageRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationAudienceRepository;
import com.alejandro.mtonotification.infrastructure.persistence.repository.NotificationRepository;
import com.alejandro.mtonotification.infrastructure.persistence.specification.ActivityEventSpecification;
import com.alejandro.mtonotification.support.PostgreSQLTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El lector de Keycloak de punta a punta contra un Keycloak 26.1 real y un PostgreSQL real: la
 * cuenta de servicio pide su token, lee los eventos de acceso y de administracion con
 * {@code view-events}, cada uno entra por el inbox y por el registro, tres fallos seguidos son una
 * racha con su aviso, un cambio hecho desde la consola es «fuera de la aplicacion», y una segunda
 * pasada no repite nada. Ademas, el directorio resuelve las direcciones de una audiencia.
 *
 * <p>El realm de {@code src/test/resources/keycloak/mto-notification-test-realm.json} lleva la
 * cuenta de servicio con los mismos roles que concede {@code mto-platform/keycloak/apply-partials.sh};
 * los eventos se activan por la Admin API una vez arrancado, como hace ese mismo guion (una
 * importacion parcial tampoco puede activarlos). Es la mitad del realm que
 * {@code KeycloakAuthorizationIT} no ve.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.rabbitmq.enabled=false",
        // El sondeo se lanza a mano desde el test, no desde el planificador.
        "app.keycloak.events.enabled=false",
        "app.keycloak.events.initial-lookback=PT1H",
        "app.notification.delivery.enabled=false",
        "app.notification.delivery.immediate-dispatch=false",
        "app.notification.burst.close-enabled=false",
        "app.notification.retention.purge-enabled=false",
        "app.notification.email.enabled=false",
        "app.notification.access.streak.threshold=3",
        "spring.security.oauth2.client.registration.mto-services.client-id=mto-notification-svc",
        "spring.security.oauth2.client.registration.mto-services.client-secret=mto-notification-svc-secret"
})
class KeycloakEventsIT extends PostgreSQLTestContainer {

    private static final String REALM = "mto";
    private static final String FRONTEND = "mto-test-frontend";
    private static final int HTTP_PORT = 8080;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

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

    /** Lo que apply-partials.sh hace en el stack: PUT /events/config con el administrador del realm master. */
    @BeforeAll
    static void enableRealmEvents() throws Exception {
        String adminToken = adminToken();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/admin/realms/" + REALM + "/events/config"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("""
                        {"eventsEnabled": true, "eventsExpiration": 604800, "eventsListeners": ["jboss-logging"],
                         "enabledEventTypes": ["LOGIN", "LOGIN_ERROR", "LOGOUT", "LOGOUT_ERROR", "UPDATE_PASSWORD", "RESET_PASSWORD",
                           "SEND_RESET_PASSWORD", "EXECUTE_ACTIONS", "EXECUTE_ACTION_TOKEN", "UPDATE_CREDENTIAL", "REMOVE_CREDENTIAL",
                           "UPDATE_TOTP", "REMOVE_TOTP", "USER_DISABLED_BY_TEMPORARY_LOCKOUT", "USER_DISABLED_BY_PERMANENT_LOCKOUT",
                           "IMPERSONATE", "UPDATE_PROFILE", "UPDATE_EMAIL"],
                         "adminEventsEnabled": true, "adminEventsDetailsEnabled": true}
                        """))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, response.statusCode(), "PUT /events/config: " + response.body());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
        registry.add("app.keycloak.base-url", KeycloakEventsIT::baseUrl);
        registry.add("app.keycloak.realm", () -> REALM);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> baseUrl() + "/realms/" + REALM);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                () -> baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token");
    }

    @Autowired
    private KeycloakEventsPoller poller;

    @Autowired
    private SourceCursorService cursorService;

    @Autowired
    private AudienceResolver audienceResolver;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationAudienceRepository audienceRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private InboxMessageRepository inboxMessageRepository;

    @Autowired
    private KeycloakEventsClient eventsClient;

    @Autowired
    private KeycloakLoginEventAdapter loginAdapter;

    @Autowired
    private KeycloakAdminEventAdapter adminAdapter;

    @Autowired
    private InboxMessageService inboxMessageService;

    @Autowired
    private ActivityIngestor activityIngestor;

    @Autowired
    private KeycloakProperties keycloakProperties;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void loginsFailuresAndConsoleChangesAreReadOnceAndTurnedIntoActivityAndNotifications() throws Exception {
        // Un acceso que va bien, tres que fallan y un cambio hecho desde fuera de la aplicacion.
        assertEquals(200, tokenRequest("lector", "lector").statusCode());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(401, tokenRequest("auditor", "mala-" + attempt).statusCode());
        }
        disableUserFromTheAdminConsole("ajeno");

        Map<SourceKind, KeycloakEventsPoller.PollSummary> first = poller.pollOnce();

        KeycloakEventsPoller.PollSummary login = first.get(SourceKind.KEYCLOAK_LOGIN);
        assertNull(login.error(), login.error());
        assertTrue(login.leased());
        assertTrue(login.ingested() >= 4, "un login y tres fallos: " + login);
        assertEquals(0, login.failed());
        KeycloakEventsPoller.PollSummary admin = first.get(SourceKind.KEYCLOAK_ADMIN);
        assertNull(admin.error(), admin.error());
        assertTrue(admin.ingested() >= 1, "el cambio de la consola: " + admin);

        // El registro: el acceso, los fallos, la racha derivada y el cambio de administracion.
        List<ActivityEvent> logins = activityEventRepository.findAll(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN)
                .and(ActivityEventSpecification.actorUsernameEquals("lector")));
        assertEquals(1, logins.size());
        assertEquals("keycloak-login", logins.getFirst().getSourceService());
        assertNotNull(logins.getFirst().getIpAddress(), "la IP del acceso se guarda");
        assertEquals(ActorKind.PERSON, logins.getFirst().getActorKind());
        assertTrue(logins.getFirst().getPayload().contains("\"clientId\": \"" + FRONTEND + "\"")
                || logins.getFirst().getPayload().contains("\"clientId\":\"" + FRONTEND + "\""), logins.getFirst().getPayload());
        assertFalse(logins.getFirst().getPayload().contains("code_id"), "solo la lista blanca de detalles");

        assertEquals(3, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_FAILED)
                .and(ActivityEventSpecification.actorUsernameEquals("auditor"))));
        List<ActivityEvent> streaks = activityEventRepository.findAll(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK)
                .and(ActivityEventSpecification.actorUsernameEquals("auditor")));
        assertEquals(1, streaks.size(), "tres fallos seguidos de una cuenta son una racha, no tres");
        ActivityEvent streak = streaks.getFirst();
        assertTrue(streak.getSourceEventId().startsWith("streak:username:auditor:"));
        // Los tres fallos salieron de la misma IP (la del host de Docker): la otra dimension es otra racha, tambien una sola.
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK)
                .and(ActivityEventSpecification.subjectTypeEquals("ip"))), "y una sola racha por la IP");

        List<ActivityEvent> consoleChanges = activityEventRepository.findAll(ActivityEventSpecification.typeEquals(ActivityTypes.USERS_ADMIN_USER_UPDATED));
        assertEquals(1, consoleChanges.size());
        ActivityEvent consoleChange = consoleChanges.getFirst();
        assertEquals("keycloak-admin", consoleChange.getSourceService());
        assertEquals(ActorKind.PERSON, consoleChange.getActorKind(), "admin-cli no es la cuenta de servicio de mto-users");
        assertEquals("user", consoleChange.getSubjectType());
        // admin-cli es un cliente del realm master: no esta en mto, asi que se queda con su id interno y con el realm de la credencial.
        assertFalse(consoleChange.getPayload().contains("\"mto-users-svc\""), consoleChange.getPayload());
        assertTrue(consoleChange.getPayload().contains("authRealmId"), consoleChange.getPayload());
        assertFalse(consoleChange.getPayload().toLowerCase().contains("password"));
        assertNull(consoleChange.getIpAddress(), "la IP no vive fuera de ACCESS");

        // Las reglas: la racha avisa (bandeja y correo) y el cambio de la consola tambien.
        Notification streakNotice = notificationForEvent(streak.getId());
        assertEquals("access-login-streak", streakNotice.getRuleKey());
        assertTrue(audienceKeys(streakNotice).contains("PROFILE:mto-users-admin"), audienceKeys(streakNotice).toString());
        List<Delivery> streakDeliveries = deliveryRepository.findByNotificationIdOrderByCreatedAtAsc(streakNotice.getId());
        assertFalse(streakDeliveries.isEmpty(), "el correo deja una entrega por audiencia, pendiente hasta que el despachador la expanda");
        assertTrue(streakDeliveries.stream().allMatch(delivery -> delivery.getScope() == DeliveryScope.AUDIENCE
                && delivery.getStatus() == DeliveryStatus.PENDING && "email".equals(delivery.getChannel())));
        // El PUT /events/config del arranque es tambien un cambio de administracion hecho por el mismo
        // administrador: dos cambios en cinco minutos son UN aviso, por el freno de la regla.
        Notification consoleNotice = notificationByRule("users-change-outside-application");
        ActivityEvent noticed = activityEventRepository.findById(consoleNotice.getActivityEventId()).orElseThrow();
        assertEquals("keycloak-admin", noticed.getSourceService());
        assertEquals(ActorKind.PERSON, noticed.getActorKind());

        // El inbox y la marca: todo procesado, las marcas avanzadas y sin arrendamiento colgado.
        assertTrue(inboxMessageRepository.countBySourceServiceAndStatus("keycloak-login", InboxMessageStatus.PROCESSED) >= 4);
        assertEquals(0, inboxMessageRepository.countBySourceServiceAndStatus("keycloak-login", InboxMessageStatus.FAILED));
        for (SourceCursor cursor : cursorService.all()) {
            assertNotNull(cursor.getLastEventTime(), cursor.getKind() + " sin marca");
            assertNotNull(cursor.getLastSuccessAt());
            assertNull(cursor.getLeaseOwner(), cursor.getKind() + " sigue arrendada");
            assertNull(cursor.getLastError());
        }

        // Segunda pasada: relee el solape y no repite nada.
        long eventsBefore = activityEventRepository.count();
        long noticesBefore = notificationRepository.count();
        Map<SourceKind, KeycloakEventsPoller.PollSummary> second = poller.pollOnce();
        assertEquals(0, second.get(SourceKind.KEYCLOAK_LOGIN).ingested(), second.toString());
        assertTrue(second.get(SourceKind.KEYCLOAK_LOGIN).duplicates() >= 4, second.toString());
        assertEquals(0, second.get(SourceKind.KEYCLOAK_ADMIN).ingested());
        assertEquals(eventsBefore, activityEventRepository.count(), "ni una linea nueva");
        assertEquals(noticesBefore, notificationRepository.count(), "ni un aviso nuevo");
        assertEquals(1, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN_STREAK)
                .and(ActivityEventSpecification.actorUsernameEquals("auditor"))));
    }

    /**
     * Mas accesos desde la marca de los que caben en un tramo (aqui, diez): el lector los lee por
     * tramos desde el mas antiguo, buscando con peticiones de un evento a que profundidad esta la
     * marca, y en una sola pasada no se deja ninguno.
     */
    @Test
    void aBurstBiggerThanOneChunkIsReadWholeFromTheOldestInOnePass() throws Exception {
        int burst = 43;
        long alreadyThere = activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN)
                .and(ActivityEventSpecification.actorUsernameEquals("responsable")));
        for (int login = 0; login < burst; login++) {
            assertEquals(200, tokenRequest("responsable", "responsable").statusCode());
        }
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger biggestPage = new AtomicInteger();
        KeycloakEventsClient counting = new KeycloakEventsClient() {
            @Override
            public List<KeycloakEvent> loginEvents(int first, int max) {
                if (max == 1) {
                    probes.incrementAndGet();
                }
                biggestPage.accumulateAndGet(max, Math::max);
                return eventsClient.loginEvents(first, max);
            }

            @Override
            public List<KeycloakAdminEvent> adminEvents(int first, int max) {
                return eventsClient.adminEvents(first, max);
            }
        };
        KeycloakProperties small = new KeycloakProperties(keycloakProperties.baseUrl(), keycloakProperties.realm(),
                keycloakProperties.clientRegistrationId(), null,
                new KeycloakProperties.Events(true, true, true, null, null, 5, 2, Duration.ZERO, Duration.ofHours(1), null, null, null, null), null);
        KeycloakEventsPollerImpl reader = new KeycloakEventsPollerImpl(counting, loginAdapter, adminAdapter, inboxMessageService,
                activityIngestor, cursorService, small, jsonMapper, new SimpleMeterRegistry(), Clock.systemUTC());

        KeycloakEventsPoller.PollSummary summary = reader.poll(SourceKind.KEYCLOAK_LOGIN);

        assertNull(summary.error(), summary.error());
        assertEquals(0, summary.failed());
        assertTrue(probes.get() > 0, "no cabia en un tramo: se busco la marca por biseccion");
        assertTrue(biggestPage.get() <= 20, "y nunca se pidio de una vez mas que un par de paginas: " + biggestPage.get());
        assertEquals(alreadyThere + burst, activityEventRepository.count(ActivityEventSpecification.typeEquals(ActivityTypes.ACCESS_LOGIN)
                .and(ActivityEventSpecification.actorUsernameEquals("responsable"))), "todos los accesos de la rafaga, ni uno menos: " + summary);

        long before = activityEventRepository.count();
        KeycloakEventsPoller.PollSummary again = reader.poll(SourceKind.KEYCLOAK_LOGIN);
        assertEquals(0, again.ingested(), again.toString());
        assertEquals(before, activityEventRepository.count(), "y la pasada siguiente no repite ninguno");
    }

    /**
     * Lo que el lector da por hecho de la Admin API de Keycloak 26.1, comprobado contra el de verdad:
     * los eventos salen del mas nuevo al mas antiguo, se paginan con first/max a cualquier profundidad,
     * no hay orden ascendente ({@code direction} se ignora) y la fecha solo filtra por dia
     * ({@code yyyy-MM-dd}; un instante es un 400). Por eso la marca se cruza paginando.
     */
    @Test
    void theAdminApiOnlyPagesNewestFirstAndFiltersByWholeDays() throws Exception {
        for (int login = 0; login < 6; login++) {
            assertEquals(200, tokenRequest("impostor", "impostor").statusCode());
        }
        String token = adminToken();

        List<Long> all = times(adminGet(token, "/events?max=500"));
        assertTrue(all.size() >= 6, all.toString());
        assertEquals(all.stream().sorted(java.util.Comparator.reverseOrder()).toList(), all, "del mas nuevo al mas antiguo");
        assertEquals(all.subList(2, 4), times(adminGet(token, "/events?first=2&max=2")), "first/max sobre ese mismo orden");
        assertEquals(List.of(all.getLast()), times(adminGet(token, "/events?first=" + (all.size() - 1) + "&max=1")), "hasta el ultimo");
        assertEquals(List.of(), times(adminGet(token, "/events?first=" + all.size() + "&max=1")), "y pasado el ultimo, vacio");
        assertEquals(all.subList(0, 3), times(adminGet(token, "/events?max=3&direction=asc")), "no hay orden ascendente");

        long anInstant = all.getLast();
        assertEquals(400, adminGet(token, "/events?dateFrom=" + anInstant).statusCode(), "la fecha no admite un instante");
        assertEquals(400, adminGet(token, "/admin-events?dateFrom=" + anInstant).statusCode());
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        assertEquals(200, adminGet(token, "/events?dateFrom=" + today.substring(0, 4) + "-01-01&max=1").statusCode(), "solo un dia");

        List<Long> adminTimes = times(adminGet(token, "/admin-events?max=500"));
        assertEquals(adminTimes.stream().sorted(java.util.Comparator.reverseOrder()).toList(), adminTimes);
        assertEquals(adminTimes.subList(0, 1), times(adminGet(token, "/admin-events?max=1&direction=asc")));
    }

    @Test
    void theDirectoryResolvesUsersProfilesAndClientRolesToAddresses() {
        assertEquals(List.of(new Recipient("lector", "lector@mto.local")), audienceResolver.resolve(Audience.user("lector")));
        assertTrue(audienceResolver.resolve(Audience.user("no-existe")).isEmpty());

        List<Recipient> auditors = audienceResolver.resolve(Audience.profile("mto-notification-auditor"));
        assertEquals(List.of(new Recipient("auditor", "auditor@mto.local")), auditors);

        Set<String> accessReaders = audienceResolver.resolve(Audience.clientRole("mto-notification-api", "notification-access-read"))
                .stream().map(Recipient::username).collect(Collectors.toSet());
        assertTrue(accessReaders.containsAll(Set.of("auditor", "responsable")),
                "los miembros de los perfiles que conceden el rol tambien cuentan: " + accessReaders);
        assertFalse(accessReaders.contains("lector"));

        Set<String> inboxUsers = audienceResolver.resolve(Audience.clientRole("mto-notification-api", "notification-inbox"))
                .stream().map(Recipient::username).collect(Collectors.toSet());
        assertTrue(inboxUsers.containsAll(Set.of("lector", "auditor", "responsable", "impostor")), inboxUsers.toString());
    }

    // --- Keycloak por HTTP ---

    private static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(HTTP_PORT);
    }

    private static HttpResponse<String> tokenRequest(String username, String password) throws Exception {
        return post(baseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token",
                "grant_type=password&client_id=" + encode(FRONTEND) + "&username=" + encode(username) + "&password=" + encode(password) + "&scope=openid");
    }

    /** El administrador del realm master, con admin-cli: lo que usa la consola o kcadm. */
    private static String adminToken() throws Exception {
        HttpResponse<String> tokenResponse = post(baseUrl() + "/realms/master/protocol/openid-connect/token",
                "grant_type=password&client_id=admin-cli&username=admin&password=admin");
        assertEquals(200, tokenResponse.statusCode(), tokenResponse.body());
        return JSON.readTree(tokenResponse.body()).get("access_token").asString();
    }

    /** Lo que haria alguien desde la consola o kcadm: un cambio con el administrador del realm master. */
    private static void disableUserFromTheAdminConsole(String username) throws Exception {
        String adminToken = adminToken();

        HttpResponse<String> users = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/admin/realms/" + REALM + "/users?username=" + encode(username) + "&exact=true"))
                .header("Authorization", "Bearer " + adminToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, users.statusCode(), users.body());
        JsonNode user = JSON.readTree(users.body()).get(0);
        String userId = user.get("id").asString();

        HttpResponse<String> update = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/admin/realms/" + REALM + "/users/" + userId))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\": false}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, update.statusCode(), update.body());
    }

    private static HttpResponse<String> adminGet(String token, String pathAndQuery) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/admin/realms/" + REALM + pathAndQuery))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Long> times(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        List<Long> times = new java.util.ArrayList<>();
        for (JsonNode event : JSON.readTree(response.body())) {
            times.add(event.get("time").asLong());
        }
        return times;
    }

    private static HttpResponse<String> post(String url, String form) throws Exception {
        return HTTP.send(HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Notification notificationForEvent(java.util.UUID activityEventId) {
        List<Notification> found = notificationRepository.findAll().stream()
                .filter(notification -> activityEventId.equals(notification.getActivityEventId())).toList();
        assertEquals(1, found.size(), "one notification for event " + activityEventId + ": " + notificationRepository.findAll());
        return found.getFirst();
    }

    private Notification notificationByRule(String ruleKey) {
        List<Notification> found = notificationRepository.findAll().stream().filter(notification -> ruleKey.equals(notification.getRuleKey())).toList();
        assertEquals(1, found.size(), "one notification for rule " + ruleKey + ": " + notificationRepository.findAll());
        return found.getFirst();
    }

    private List<String> audienceKeys(Notification notification) {
        return audienceRepository.findByIdNotificationIdIn(List.of(notification.getId())).stream()
                .map(NotificationAudience::getId).map(NotificationAudience.Id::getAudienceKey).sorted().toList();
    }
}
