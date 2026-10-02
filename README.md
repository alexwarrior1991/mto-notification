# MTO Notification

API Spring Boot 4 / Java 25 del dominio MTO que guarda el **registro de actividad** del dominio (qué
fue, quién, cuándo, desde qué servicio y sobre qué entidad) y deriva de él las **notificaciones**: una
bandeja por persona dentro de la aplicación y correo para lo urgente, según unas reglas que dicen
qué merece un aviso, a quién y por qué canal.

Es el séptimo servicio del dominio, junto a
[`mto-configuration`](https://github.com/alexwarrior1991/mto-configuration) (datos maestros),
[`mto-stock`](https://github.com/alexwarrior1991/mto-stock) (almacén),
[`mto-maintenance`](https://github.com/alexwarrior1991/mto-maintenance) (mantenimiento) y
[`mto-users`](https://github.com/alexwarrior1991/mto-users) (usuarios), publicado a través de
[`mto-gateway`](https://github.com/alexwarrior1991/mto-gateway), consumido por
[`mto-backoffice`](https://github.com/alexwarrior1991/mto-backoffice) y desplegado con
[`mto-platform`](https://github.com/alexwarrior1991/mto-platform).

La documentación funcional y técnica vive en [`docs/`](docs/README.md).

## Estado

Fases 2a a 2d: el servicio funciona con cuatro fuentes. El registro (`V1`), el inbox idempotente,
los datos maestros de `mto-configuration` por su cola propia (con ráfagas: una importación es una
línea), los trabajos de `mto-configuration` (`job.finished`, un aviso a quien lo lanzó), las
acciones administrativas de `mto-users` con la persona que las hizo, el lector de eventos de
Keycloak (accesos y administración del realm, con rachas de accesos fallidos) y el correlador que
funde el evento de Keycloak con el de `mto-users` del mismo cambio, las reglas en YAML, la bandeja
por persona, el correo por Mailpit, la retención y la API de administración, y lo que publican
`mto-maintenance` y `mto-stock`. Quedan la campana y las pantallas del backoffice
([`docs/05-development-roadmap.md`](docs/05-development-roadmap.md)).

## Qué hace

1. **Ingiere.** Cada fuente entra por el mismo `inbox_message` (idempotente por mensaje y fuente) y
   deja **una** línea normalizada en `activity_event` (idempotente por origen e id): tipo
   `<categoría>.<sujeto>.<evento>`, gravedad, actor, sujeto, correlación y un `payload` por lista
   blanca. Nunca una contraseña, un token ni un secreto.
2. **Deriva.** Tres accesos fallidos del mismo usuario o de la misma IP en diez minutos son una
   racha; mil perfiles modificados por una importación son una ráfaga con `eventCount`; un cambio
   hecho desde `mto-users` se registra una vez, con nombre, y el evento de Keycloak del mismo
   cambio queda fundido con él (`supersededBy`).
3. **Avisa.** Las reglas de `notification-rules.yml` casan el evento, evalúan una condición y
   crean la notificación con sus audiencias (`USER:`, `USER_ID:`, `PROFILE:`, `CLIENT_ROLE:`) y
   sus canales.
4. **Entrega.** La bandeja se resuelve al leer, con el token de la persona. El correo sale por un
   despachador con reintentos, una entrega por destinatario, y las direcciones las da Keycloak.

## Requisitos

- JDK 25
- PostgreSQL 17 (cualquier 16+ vale; `mto-platform` corre 17)
- Docker, para los tests con Testcontainers y para el entorno local
- RabbitMQ, Keycloak y Mailpit, los tres de `mto-platform`

## Configuración

Todo se lee del entorno; `.env.example` lista cada variable con su valor por defecto. Las que no lo
tienen:

| Variable | Qué |
|---|---|
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | La base de la aplicación |
| `KEYCLOAK_ISSUER_URI` | El realm que emite los tokens (`http://auth.mto.local:8082/realms/mto`) |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_AUDIENCE` | `mto-notification-api` |
| `KEYCLOAK_AUTH_SERVER_URL`, `KEYCLOAK_REALM`, `KEYCLOAK_TOKEN_URI` | La Admin API de Keycloak y el endpoint de token de la cuenta de servicio |
| `KEYCLOAK_SERVICE_CLIENT_SECRET` | Secreto de la cuenta de servicio `mto-notification-svc`, con la que se leen los eventos de Keycloak y las direcciones de una audiencia |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | El SMTP (Mailpit en local: `1025`, bandeja web en `8025`) |
| `APP_NOTIFICATION_LINK_BASE_URL` | Raíz absoluta de los enlaces del correo: el backoffice |

Interruptores: `APP_RABBITMQ_ENABLED=false` arranca sin broker, `APP_KEYCLOAK_EVENTS_ENABLED=false`
no sondea Keycloak (y arranca sin secreto), `APP_NOTIFICATION_EMAIL_ENABLED=false` deja los avisos
en la bandeja, `APP_NOTIFICATION_DELIVERY_ENABLED`/`_BURST_CLOSE_ENABLED`/`_RETENTION_PURGE_ENABLED`
apagan el trabajo de fondo en una instancia, `APP_SECURITY_EXPOSE_API_DOCS=true` publica Swagger
sin token. Las reglas se sobrescriben con `APP_NOTIFICATION_RULES_LOCATION` (`file:/ruta.yml`) y
sus variables con `app.notification.rules.variables.*`.

## Perfiles de Spring

`dev` (puerto 8086, Swagger abierto, secreto de servicio fijo), `test` (broker, lector,
despachador, cierre de ráfagas, purga y correo apagados: cada test enciende lo que prueba
llamándolo) y `prod` (parada ordenada, detalle de health oculto, sin valores por defecto para base,
broker, Keycloak, SMTP, enlace del correo ni secreto).

## Arrancar en local

```bash
cd ../mto-platform && docker compose up -d && ./keycloak/apply-partials.sh
cd ../mto-notification
export SPRING_PROFILES_ACTIVE=dev
export DATABASE_URL=jdbc:postgresql://localhost:5432/mto_notification
export DATABASE_USERNAME=mto_notification_user DATABASE_PASSWORD=mto_notification_password
export KEYCLOAK_ISSUER_URI=http://auth.mto.local:8082/realms/mto
export SPRING_RABBITMQ_USERNAME=mto SPRING_RABBITMQ_PASSWORD=mto
./mvnw spring-boot:run
```

Con eso el lector empieza a sondear Keycloak a los 15 s (la cuenta `mto-notification-svc` con el
secreto de desarrollo), el consumidor escucha `mto.notification.master-data.queue` y el correo sale
por el Mailpit de `mto-platform` (http://localhost:8025). `GET /api/v1/notifications/admin/sources`
con un token de `notificacion.responsable` enseña las marcas avanzando.

`auth.mto.local` tiene que resolver al host (`127.0.0.1 auth.mto.local` en `/etc/hosts`): el `iss`
del token lleva ese nombre y la aplicación descarga de él el JWK Set.

Todo el stack, este servicio incluido, corre también desde `mto-platform` con
`docker compose --profile all up -d` (imagen publicada). El `compose.yaml` de aquí construye y arranca
una imagen local contra esa infraestructura:

```bash
cp .env.example .env    # rellenar DATABASE_*, KEYCLOAK_SERVICE_CLIENT_SECRET
docker compose up -d --build
```

## Migraciones

Flyway, `src/main/resources/db/migration`. Hibernate valida el esquema al arrancar, así que cada
cambio es un `V<n>__*.sql` nuevo. `V1` crea las diez tablas: el inbox, el registro, las ráfagas,
las notificaciones con sus audiencias y recibos, la marca de «todas leídas», las entregas, los
frenos por regla y la marca del lector. Detalle en [`docs/03-database.md`](docs/03-database.md).

## Las fuentes

| Fuente | Cómo llega | Detalle |
|---|---|---|
| Datos maestros de `mto-configuration` | Cola propia `mto.notification.master-data.queue` sobre `mto.master-data.exchange`, con DLX/DLQ y firma | Las altas y modificaciones se agregan en ráfagas; las bajas de infraestructura y el alta de un paquete son una línea cada una |
| Trabajos de `mto-configuration` | Cola propia `mto.notification.configuration.queue` sobre `mto.configuration.exchange` (`mto.configuration.#`) | `job.finished`: una línea por trabajo con sus recuentos y un aviso a quien lo lanzó; `WARNING` si acabó mal |
| `mto-users` | Cola propia `mto.notification.users.queue` sobre `mto.users.exchange` (`mto.users.#`) | Una línea por acción administrativa con la persona; el evento de administración de Keycloak del mismo cambio se funde con ella |
| Accesos y administración de Keycloak | Sondeo de la Admin API cada 20 s con `mto-notification-svc` (`view-events`), marca de agua por fuente y arrendamiento | Idempotente por huella del evento; tres fallos seguidos son una racha; un cambio hecho desde la consola es «fuera de la aplicación» |
| `mto-maintenance` | Cola propia `mto.notification.maintenance.queue` sobre `mto.maintenance.exchange` (`mto.maintenance.#`) | Una línea por evento de su outbox (órdenes y sus transiciones, defectos, inspecciones, turnos, líneas de material, activos desactivados allí, el aviso diario de preventivos) con la persona y la correlación de la petición |
| `mto-stock` | Cola propia `mto.notification.stock.queue` sobre `mto.stock.exchange` (`mto.stock.#`) | Un material cuyo disponible total cae por debajo de su mínimo (solo al cruzar), una reserva cancelada o liberada (con quien la creó) y un ajuste de inventario, con la persona o la cuenta de servicio de `mto-maintenance` y la correlación de la petición |

Todo en [`docs/06-messaging.md`](docs/06-messaging.md), reglas incluidas.

## Las reglas

`src/main/resources/notification-rules.yml`: `event` (tipo, lista o `maintenance.order.*`), `when`
(SpEL sobre `event`, `payload` y `vars`), `severity`, `audiences` (`PROFILE:mto-ops`,
`USER:#{event.actorUsername}`, `USER_ID:#{payload.targetUserId}`), `channels` (`inbox`, `email`),
`title`/`body`/`link` (plantillas)
y `throttle`. Se validan al arrancar: un tipo, una audiencia o un canal desconocidos impiden
arrancar, que es como una errata no se descubre el día que el evento por fin llega.
`GET /admin/rules` enseña las cargadas.

## Seguridad

Resource server de Keycloak, audiencia `mto-notification-api`. Los permisos son roles de cliente y
los perfiles, compuestos de realm (`mto-notification-viewer`, `-auditor`, `-admin`); los perfiles de
los demás servicios llevan también `notification-inbox`, porque la campana es de todo el mundo.
Ver [`keycloak/README.md`](keycloak/README.md).

| Recurso | Permiso |
|---|---|
| `/inbox/**` | `notification-inbox` |
| `GET /activity/**` | `notification-activity-read` |
| `GET /access/**` | `notification-access-read` |
| `/admin/**` | `notification-admin` |
| `/actuator/**` (salvo health/info) | `ops-metrics` / `ops-write` |

Sin CORS propio: todo navegador llega por `mto-gateway`, que resuelve el CORS y quita `Origin` antes
de llamar a este servicio. Aquí no hay `.cors()` ni `OPTIONS` abierto, así que un *preflight* que
llegara directamente pide token como cualquier otra petición (`ApiAuthorizationRulesTest`).

```bash
TOKEN=$(curl -s -X POST http://auth.mto.local:8082/realms/mto/protocol/openid-connect/token \
  -d grant_type=password -d client_id=mto-frontend \
  -d username=notificacion.auditor -d password=local | jq -r .access_token)
curl -s http://localhost:8086/api/v1/notifications/inbox -H "Authorization: Bearer $TOKEN"
```

## Documentación de la API

Swagger UI en `/swagger-ui.html`, OpenAPI en `/v3/api-docs` (abiertos en `dev`, tras
`APP_SECURITY_EXPOSE_API_DOCS` en el resto). Los recursos, en
[`docs/04-rest-api.md`](docs/04-rest-api.md); `http/notification-api.http` recorre la bandeja y el
registro desde el IDE.

## Actuator y trazas

`/actuator/health` y `/actuator/info` son públicos; `metrics` y `prometheus` piden `ops-metrics`.
Las trazas van al colector OTLP de `mto-platform` (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`), incluida la
que llega en las cabeceras de RabbitMQ.

## Tests

```bash
./mvnw test                     # Testcontainers: postgres:17-alpine; GreenMail para el correo
./mvnw verify                   # + KeycloakAuthorizationIT y KeycloakEventsIT (Keycloak 26.1; se saltan sin Docker)
```

`KeycloakEventsIT` es el lector de punta a punta: un Keycloak real con los eventos activados, un
acceso, tres fallos y un cambio desde la consola, y de ahí el registro, la racha, sus avisos y la
segunda pasada que no repite nada.

Sin Docker, la suite se apunta a cualquier PostgreSQL:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/mto_notification_test \
TEST_DATABASE_USERNAME=mto_notification TEST_DATABASE_PASSWORD=mto_notification ./mvnw test
```
