# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Proyecto

`mto-notification`: registro de actividad y notificaciones del dominio `MTO` (infraestructura
ferroviaria de catenaria). Una API **Spring Boot 4 / Java 25** que guarda, normalizado y
append-only, todo lo importante que pasa en el dominio (qué fue, quién, cuándo, desde qué servicio y
sobre qué entidad) y que, con unas reglas, decide qué merece un aviso, a quién (un usuario, un rol
de cliente o un perfil; más adelante un equipo o una zona para `mto-field`) y por qué canal (la
bandeja de la aplicación, con leídas y no leídas por persona, y el correo para lo urgente; más
adelante push). `README.md` es la referencia funcional y operativa; `docs/` va numerado y en orden
de lectura; `keycloak/README.md` explica el lado del realm.

⚠️ `mto-configuration`, `mto-stock`, `mto-maintenance`, `mto-users`, `mto-gateway` y
`mto-backoffice` son **repos hermanos independientes**. La infraestructura local (PostgreSQL,
RabbitMQ, Keycloak, Mailpit, el colector de trazas) la levanta `mto-platform`, cuyo
`keycloak/apply-partials.sh` aplica `keycloak/mto-notification-partial-import.json` **la primera**
(los perfiles de los demás nombran sus roles), concede a `mto-notification-svc` sus roles de
`realm-management` y activa los eventos del realm. `compose.yaml` aquí trae **solo la aplicación**.

Documentación en castellano, código en inglés, commits en castellano.

## Comandos

```bash
./mvnw compile
./mvnw test                                          # sin Docker: los tests con Postgres se saltan
./mvnw verify                                        # + KeycloakAuthorizationIT y KeycloakEventsIT (Keycloak 26.1 en Testcontainers; se saltan sin Docker)
./mvnw test -Dtest=ApiAuthorizationRulesTest         # una clase
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/mto_notification_test \
TEST_DATABASE_USERNAME=... TEST_DATABASE_PASSWORD=... ./mvnw verify   # sin Docker, con un Postgres a mano
./mvnw spring-boot:run                               # perfil dev: puerto 8086, Swagger abierto
```

Entorno local: `cd ../mto-platform && docker compose --profile all up -d && ./keycloak/apply-partials.sh`.
Perfiles `dev` (por defecto), `test`, `prod`. Puerto **8086** (`dev`); el contenedor escucha en 8080
y `mto-platform` lo publica en 8086. Detrás del gateway el prefijo público es `/api/notifications`.

- Flyway corre al arrancar sobre `src/main/resources/db/migration`; Hibernate es `ddl-auto: validate`
  en todos los perfiles, así que un cambio de esquema es siempre una migración nueva.
- Swagger UI: `http://localhost:8086/swagger-ui.html` (`dev`); OpenAPI JSON: `/v3/api-docs`.

## Arquitectura

Las mismas tres capas que `mto-maintenance` bajo `com.alejandro.mtonotification`:

- `domain/model` — reglas sin framework: `ActivityTypes` (el catálogo de tipos; una regla que
  nombre uno que no existe impide arrancar), `ActivityCategory` (se deriva del tipo),
  `ActivityEventDraft` (el borrador que se ingiere: valida, descarta la IP fuera de `ACCESS` y pasa
  el payload por `PayloadSanitizer`), `Actor`, `Subject`, `Audience`/`AudienceKind`
  (`KIND:clave`), `DeliveryChannels`, `NotificationRule` + `EventTypeMatcher`, `Fingerprints`.
- `application` — `dto` (un paquete por recurso; `common/PageResponse`, `error/ApiErrorResponse`,
  `messaging/SourceEnvelope`, `keycloak/*`), `service` + `service/impl` (impls package-private tras
  interfaces públicas), `mapper` (MapStruct, **entidad → respuesta solo**), `exception`.
  Las piezas de `service/impl`, en el orden en que pasa un evento: `SourceEventConsumer` (RabbitMQ)
  o `KeycloakEventsPollerImpl` → `InboxMessageServiceImpl` (`IdempotentSourceEventProcessor`) →
  `DispatchingSourceEventHandler` → un `ActivitySourceAdapter` por fuente de RabbitMQ
  (`MasterDataSourceAdapter`, `ConfigurationSourceAdapter`, `UsersSourceAdapter`; los de Keycloak,
  `KeycloakLoginEventAdapter` y `KeycloakAdminEventAdapter`, los llama el lector) →
  `ActivityIngestorImpl` (`insert ... on conflict do nothing`; si insertó: `RuleEngineImpl` y los
  `DerivedEventDetector`, hoy `FailedLoginStreakDetector` y `UsersChangeCorrelator`) →
  `NotificationFactoryImpl` (notificación, audiencias y una
  entrega `AUDIENCE` por canal que empuja) → `DeliveryDispatcherImpl` (fuera de transacción:
  reclama, expande con `KeycloakDirectoryAudienceResolver`, envía por el `DeliveryChannel`, marca
  a través de `DeliveryRelayServiceImpl`). Aparte: `BurstAggregatorImpl` (ráfagas y su cierre),
  `YamlRuleRepository` + `RuleExpressionEvaluator` (SpEL de solo lectura), `ThrottleGateImpl`,
  `SourceCursorServiceImpl`, `InboxQueryServiceImpl`, `ActivityQueryServiceImpl`,
  `AdminServiceImpl`, `RetentionPurgeImpl`.
- `infrastructure` — `persistence` (JPA, repositorios con SQL nativo condicional, specifications),
  `web` (`NotificationApiPaths`, controladores, `GlobalExceptionHandler`), `messaging/rabbitmq`
  (`SourceEventConsumer`, un `@RabbitListener` por fuente), `keycloak` (`RestKeycloakEventsClient`,
  `RestKeycloakDirectoryClient` sobre `RestClient`, circuito `keycloak`), `mail` (`EmailChannel`).
- `configuration` — `security` (resource server de Keycloak, las mismas piezas que los hermanos;
  `CurrentUserService.getAudienceKeys()` da las claves de la bandeja), `web` (`CorrelationIdFilter`),
  auditoría JPA, OpenAPI, `rabbitmq` (`SourceRabbitProperties`: una fuente por clave), `messaging`
  (firma), `keycloak` (`KeycloakProperties`, el cliente con la cuenta de servicio, el sondeo),
  `notification` (`NotificationProperties`), `mail`, `scheduling` (ráfagas, entregas, retención).

### Seguridad

`SecurityConfiguration` es la de los hermanos (`KeycloakJwtAuthenticationConverter`: roles de
cliente de `mto-notification-api` → `ROLE_X` y `ROLE_CLIENT_X`, roles de realm **solo**
`ROLE_REALM_X`; `JwtAudienceValidator`; `CurrentUserService`; 401/403 por el advice), pero aquí el
permiso lo decide el **recurso**, no el verbo, y ninguno implica a otro (`ApiAuthorizationRulesTest`):

| Ruta bajo `/api/v1/notifications` | Rol |
|---|---|
| `/inbox/**` (todos los verbos: leer y marcar son el estado propio) | `notification-inbox` |
| `GET`/`HEAD /activity/**` | `notification-activity-read` |
| `GET`/`HEAD /access/**` (usuario e IP: permiso aparte) | `notification-access-read` |
| `/admin/**` | `notification-admin` |
| cualquier otra cosa bajo la API | **denegada**: un recurso nuevo declara su permiso o no existe |

Un rol nuevo va a `SecurityRoles` **y** a `keycloak/mto-notification-partial-import.json` (y a
`mto-platform/keycloak/mto-ops-cross-service.json` si es un `ops-*`).

### Errores y paginación

`ApiErrorResponse(timestamp, status, error, message, path, method, errorCode, correlationId,
validationErrors[{field, message}])`, el mismo de `mto-maintenance`, que el backoffice ya lee.
Códigos comunes `REQ-VALIDATION`, `REQ-400`, `REQ-415`, `REQ-405`, `AUTH-401`, `AUTH-403`,
`HTTP-404`, `APP-409`, `APP-500`; propios por agregado (`NTF-404`, `ACT-404`, `DLV-404`...) en
`BusinessErrorCodeResolver`. Paginación `PageResponse<T>{content, page{number, size, totalElements,
totalPages, first, last}}` vía `PageMapper`; un `sort` desconocido es 400 `REQ-400`.

### Reglas que no se rompen

- **Nunca contraseñas, tokens ni secretos** en el registro: los detalles de Keycloak pasan por la
  lista blanca de `KeycloakLoginEventAdapter`, la representación de un evento de administración se
  descarta salvo los nombres de rol, y `PayloadSanitizer` tira cualquier clave que huela a
  credencial a cualquier profundidad. El JSON crudo solo vive en el inbox (7 días).
- **Los accesos (`ACCESS`) nunca salen por `/activity`**: tienen su endpoint y su permiso, y la IP
  solo vive en esa categoría (`CHECK` del esquema y el borrador).
- **La idempotencia es de la base**, no del código: el inbox por `(message_id, source_service)`, el
  registro por `(source_service, source_event_id)`, las ráfagas por el índice único parcial de las
  abiertas, las entregas por audiencia y por destinatario, los frenos por `(rule_key, dimension_key)`
  y el arrendamiento del lector por recuento de filas; nunca leer-y-escribir. Un derivado lleva una
  clave calculable (`streak:<dimensión>:<valor>:<inicio de ventana>`, `burst:<id>`) para que dos
  instancias o dos pasadas no lo dupliquen.
- **A quién le toca se resuelve al leer, con el token** (`USER:`, `USER_ID:` por el `sub`,
  `PROFILE:` por cada rol de realm, `CLIENT_ROLE:` por cada rol de cliente); nada se expande al
  crear. «Marcar todas» va hasta la más reciente visible, no hasta ahora; el recibo gana a la marca.
  `USER_ID` existe porque `mto-users` no siempre sabe el nombre del usuario objetivo (roles,
  perfiles, sesiones) y un adaptador no puede preguntarlo a Keycloak: corre dentro de la
  transacción del inbox.
- **Los eventos propios de un servicio son `DomainEvent`** (`entityName`, `entityId`, `eventName`,
  `values`) y el tipo de la línea sale de ahí (`<categoría>.<entityName>.<eventName>`), como la
  clave de enrutado; `users.admin.*` es de Keycloak y un productor que lo use va a la DLQ. Los
  ejemplos que cada productor versiona (`docs/messaging/examples/` de `mto-configuration` y
  `mto-users`) están copiados en `src/test/resources/contracts/<productor>/` y son lo que
  `BusinessLayerTest` y `MessagingLayerTest` hacen pasar por los adaptadores: un cambio de contrato
  se copia aquí en el mismo cambio.
- **Un cambio hecho desde `mto-users` se registra una vez con nombre**: su evento (con la persona)
  y el de administración de Keycloak del mismo cambio (con `mto-users-svc`) se funden marcando el
  de Keycloak con `superseded_by` (`UsersChangeCorrelator`, ventana
  `app.notification.users.correlation-window`, cualquier orden de llegada, actualización
  condicional). El de Keycloak con actor `PERSON` (consola, `kcadm`) nunca se funde: es el «cambio
  fuera de la aplicación» que sí avisa. Las consultas esconden lo fundido salvo `includeSuperseded`.
- **Las reglas se validan al arrancar** (`YamlRuleRepository`): clave repetida, tipo fuera de
  `ActivityTypes`, audiencia o canal desconocidos impiden arrancar. Una regla que falla al
  evaluarse se salta y se registra; las demás siguen. Un tipo nuevo va a `ActivityTypes` antes que
  a una regla.
- **Todo el HTTP hacia Keycloak corre fuera de transacción** (el lector: arrendamiento → HTTP →
  una transacción por evento → avance de la marca; el despachador: reclamar → resolver → enviar →
  marcar). Keycloak caído solo retrasa el correo y deja la marca donde estaba.
- **Lo que corre solo se apaga en los tests** (`application-test.yml`: lector, despachador, cierre
  de ráfagas, purga, correo) y cada test enciende lo que prueba llamándolo. `MtoNotificationApplicationTests`
  y `KeycloakEventsIT` ponen esas properties a mano porque corren con el perfil por defecto.
- **El `X-Correlation-Id`** lo pone `CorrelationIdFilter` en el MDC y en cada error; es la referencia
  que ata una línea del registro a la petición que la causó.

## Tests

Una clase por capa; se añaden métodos, no clases: `DomainModelTest`, `RulesConfigurationTest` (el
YAML real carga; una regla rota impide arrancar), `BusinessLayerTest` (motor, ingesta, detector de
rachas, datos maestros, los trabajos de configuración y las acciones de `mto-users` con los
ejemplos de `src/test/resources/contracts`, el correlador, adaptadores y lector de Keycloak,
despachador y resolutor; con dobles), `MessagingLayerTest` (el JSON literal de `mto-configuration`
y los ejemplos de cada productor, consumidor, inbox, firma y topología de las tres fuentes con
`ApplicationContextRunner`), `KeycloakEventsClientTest` (`MockRestServiceServer`),
`MailLayerTest` (GreenMail), `MapperLayerTest`, `DtoValidationTest`, `JpaEntityModelTest`,
`RestControllerLayerTest` (`@WebMvcTest` de los cuatro controladores con la cadena real y
`jwt()`), `GlobalExceptionHandlerTest`, `SecurityLayerTest`, `ApiAuthorizationRulesTest`
(controladores sonda), `ApiDocsExposureTest`, `CorrelationIdFilterTest`,
`OpenApiDocumentationConfigurationTest`; contra PostgreSQL, `InboxMessageRepositoryDataJpaTest`,
`ActivityRegistryDataJpaTest` (idempotencia, `CHECK`s, rachas, la fusión del evento de Keycloak con
el de `mto-users` en los dos órdenes, ráfagas con su cierre en carrera, frenos, arrendamiento, purga) y `NotificationInboxDataJpaTest` (factoría, bandeja, recibos, marca,
entregas), los tres `@DataJpaTest` que recogen los servicios package-private con una
`@TestConfiguration` anidada y `@ComponentScan` por nombre; `MtoNotificationApplicationTests`
(contexto completo contra un PostgreSQL real, sin mocks: cada servicio nuevo añade aquí su bean);
y en `verify`, `KeycloakAuthorizationIT` y `KeycloakEventsIT` (Keycloak 26.1 en Testcontainers con
`src/test/resources/keycloak/mto-notification-test-realm.json`, la misma forma que `keycloak/`,
con la cuenta de servicio, copiado en el contenedor como `mto-realm.json` porque `DirImportProvider`
saca el nombre del realm del nombre del fichero y, con otro, la cuenta de servicio muere con
«Session not bound to a realm»; el segundo activa los eventos del realm por la Admin API al
arrancar, como `apply-partials.sh`, hace un acceso, tres fallos y un cambio desde la consola y
comprueba el registro, la racha, los avisos y que la segunda pasada no repite nada). `support/PostgreSQLTestContainer` levanta `postgres:17-alpine` o usa
`TEST_DATABASE_URL/USERNAME/PASSWORD`; sin ninguna de las dos cosas la clase se omite, no falla.
