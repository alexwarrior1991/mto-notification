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
./mvnw verify                                        # + KeycloakAuthorizationIT (Keycloak 26.1 en Testcontainers; se salta sin Docker)
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

- `domain/model` — reglas sin framework (tipos de evento, audiencias, casado de reglas).
- `application` — `dto` (un paquete por recurso; `common/PageResponse`, `error/ApiErrorResponse`),
  `service` + `service/impl` (impls package-private tras interfaces públicas), `mapper` (MapStruct,
  **entidad → respuesta solo**), `exception` (`BusinessException` y sus hijas).
- `infrastructure` — `persistence` (JPA, repositorios con SQL nativo condicional, specifications),
  `web` (`NotificationApiPaths`, controladores, `GlobalExceptionHandler`), `messaging/rabbitmq`,
  `keycloak` (el lector de eventos y el directorio, sobre `RestClient`), `mail`.
- `configuration` — `security` (resource server de Keycloak, las mismas piezas que los hermanos),
  `web` (`CorrelationIdFilter`), auditoría JPA, OpenAPI y, por fases, RabbitMQ, firma, Keycloak,
  correo, reglas y planificadores.

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

- **Nunca contraseñas, tokens ni secretos** en el registro: lo que llega de Keycloak y de los
  eventos pasa por lista blanca antes de guardarse.
- **Los accesos (`ACCESS`) nunca salen por `/activity`**: tienen su endpoint y su permiso.
- **La idempotencia es de la base**, no del código: el inbox por `(message_id, source_service)` y el
  registro por `(source_service, source_event_id)`; nunca leer-y-escribir.
- **El `X-Correlation-Id`** lo pone `CorrelationIdFilter` en el MDC y en cada error; es la referencia
  que ata una línea del registro a la petición que la causó.

## Tests

Una clase por capa; se añaden métodos, no clases: `SecurityLayerTest`, `ApiAuthorizationRulesTest`
(controladores sonda con la forma de las rutas reales y `jwt()`), `ApiDocsExposureTest`,
`KeycloakAuthorizationIT` (Keycloak 26.1 en Testcontainers con
`src/test/resources/keycloak/mto-notification-test-realm.json`, la misma forma que `keycloak/`),
`CorrelationIdFilterTest`, `OpenApiDocumentationConfigurationTest`, `GlobalExceptionHandlerTest` y
`MtoNotificationApplicationTests` (el único `@SpringBootTest`: contexto completo contra un
PostgreSQL real, sin mocks). `support/PostgreSQLTestContainer` levanta `postgres:17-alpine` o usa
`TEST_DATABASE_URL/USERNAME/PASSWORD`; sin ninguna de las dos cosas la clase se omite, no falla.
