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

Fase 1: el esqueleto. Seguridad, contrato de errores, correlación, OpenAPI, imagen, CI y el lado
del realm están; el modelo, las fuentes, las reglas, la bandeja y el correo llegan en la fase 2
([`docs/05-development-roadmap.md`](docs/05-development-roadmap.md)).

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
| `KEYCLOAK_SERVICE_CLIENT_SECRET` | Secreto de la cuenta de servicio `mto-notification-svc`, con la que se leen los eventos de Keycloak |
| `APP_CORS_ALLOWED_ORIGIN` | Origen de navegador permitido por CORS |

Interruptores: `APP_RABBITMQ_ENABLED=false` arranca sin broker, `APP_SECURITY_EXPOSE_API_DOCS=true`
publica Swagger sin token.

## Perfiles de Spring

`dev` (puerto 8086, Swagger abierto), `test` (el de la suite: broker apagado, validación de
audiencia apagada) y `prod` (parada ordenada, detalle de health oculto, sin valores por defecto para
base, broker, Keycloak ni secreto).

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
cambio es un `V<n>__*.sql` nuevo. Detalle en [`docs/03-database.md`](docs/03-database.md).

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
./mvnw test                     # Testcontainers: postgres:17-alpine
./mvnw verify                   # + KeycloakAuthorizationIT (se salta sin Docker)
```

Sin Docker, la suite se apunta a cualquier PostgreSQL:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/mto_notification_test \
TEST_DATABASE_USERNAME=mto_notification TEST_DATABASE_PASSWORD=mto_notification ./mvnw test
```
