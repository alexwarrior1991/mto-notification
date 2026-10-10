# 03 · Base de datos

Flyway sobre `src/main/resources/db/migration`; Hibernate en `ddl-auto: validate` en todos los
perfiles, así que un cambio de esquema es siempre una migración nueva.

## Convenciones

- Ids `uuid`, columnas `created_at`/`updated_at`/`created_by`/`updated_by` (`AuditableEntity`),
  tablas en snake_case, enumerados cerrados como tipos de PostgreSQL (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`),
  `jsonb` para los payloads normalizados (`@JdbcTypeCode(SqlTypes.JSON)`), `timestamptz` para los
  instantes, `inet` para las IP.
- Los conjuntos que crecen por configuración (clases de audiencia, canales) son `varchar` validados
  por el registro Java, no enumerados de PostgreSQL: añadir uno no es un `ALTER TYPE`.
- Sin Envers: el registro es append-only y su historia es él mismo.
- Lo que decide una idempotencia o una carrera se decide **dentro de la sentencia que escribe**
  (`insert ... on conflict do nothing`, `update ... where` con recuento de filas,
  `for update skip locked`), nunca leyendo y escribiendo después. Esas tablas se escriben solo con
  SQL nativo y llevan `system` de actor.

## Enumerados

`inbox_message_status` (`RECEIVED`, `PROCESSING`, `PROCESSED`, `FAILED`), `activity_category`
(`ACCESS`, `USERS`, `CONFIGURATION`, `MAINTENANCE`, `STOCK`, `SYSTEM` en `V1`; `FIELD` lo añade `V2`
con `ALTER TYPE ... ADD VALUE`, que corre dentro de la transacción de Flyway porque la migración no
usa el valor nuevo), `activity_severity` (`INFO`,
`WARNING`, `CRITICAL`), `actor_kind` (`PERSON`, `SERVICE`, `SYSTEM`), `activity_burst_status`
(`OPEN`, `CLOSED`), `delivery_scope` (`AUDIENCE`, `RECIPIENT`), `delivery_status` (`PENDING`,
`IN_PROGRESS`, `SENT`, `FAILED`, `SKIPPED`).

## `V1__create_notification_schema.sql`

| Tabla | Para qué | Lo que la sostiene |
|---|---|---|
| `inbox_message` | Copia del de `mto-maintenance`; sirve a RabbitMQ **y** al lector de Keycloak | `unique (message_id, source_service)`; `payload` es `json` (los bytes tal cual, para la firma); índices por estado y `received_at` (la purga) y por fuente y estado (la vista de administración) |
| `activity_event` | El registro: `seq` (`identity`, orden de llegada), origen, `category`, `type`, `severity`, `occurred_at`, `recorded_at`, actor, sujeto, `correlation_id`, `ip_address inet`, `event_count`, `payload jsonb`, `superseded_by` | `unique (source_service, source_event_id)` = **la** idempotencia; `check` de IP solo en `ACCESS`, de la forma del tipo (`^[a-z0-9-]+(\.[a-z0-9-]+)+$`) y de `event_count >= 1`; índices `(category, occurred_at desc)`, `(type, occurred_at desc)`, `(actor_username, occurred_at desc)`, `(subject_type, subject_id, occurred_at desc)`, parcial `(ip_address, occurred_at desc) where category = 'ACCESS'` y `(category, recorded_at)` para la purga |
| `activity_burst` | Ráfagas de datos maestros: `burst_key`, `status`, entidad, operación, actor, `correlation_id`, `event_count`, `sample_ids jsonb`, `opened_at`, `last_event_at`, `closed_at` | Índice único parcial `(burst_key) where status = 'OPEN'`: destino del upsert y lo que hace que solo un cerrador gane; parciales por `last_event_at` de las abiertas y `closed_at` de las cerradas |
| `notification` | `rule_key` (`manual-broadcast`, `test-email` en las manuales), `activity_event_id` (`on delete set null`), `category`, `severity`, `title`, `body`, `link`, sujeto | `(created_at desc, id desc)`, `(activity_event_id)` |
| `notification_audience` | `kind` y `audience_key` (`USER:alice`, `USER_ID:<id de Keycloak>`, `PROFILE:mto-ops`, `CLIENT_ROLE:mto-stock-api:stock-read`), `created_at` desnormalizado | `pk (notification_id, audience_key)`; `(audience_key, created_at desc, notification_id desc)` para la bandeja con claves estrechas |
| `notification_receipt` | Leída por persona | `pk (notification_id, username)`; el recibo gana a la marca |
| `inbox_state` | «Marcar todas» en O(1): `all_read_until` por `username` | Upsert que solo avanza (`greatest`) |
| `delivery` | Entregas de los canales que empujan: `channel varchar`, `scope`, `audience_kind`/`audience_key` o `recipient`/`recipient_username`, `status`, `attempts`, `max_attempts`, `next_attempt_at`, `claimed_at`, `sent_at`, `last_error` | Únicos parciales `(notification_id, channel, audience_key) where scope = 'AUDIENCE'` y `(notification_id, channel, recipient) where scope = 'RECIPIENT'` = idempotencia por notificación, canal y destinatario; `check` de las columnas de cada `scope`; parcial `(next_attempt_at) where status in ('PENDING', 'IN_PROGRESS')` para el reclamo; `(channel, recipient, sent_at) where status = 'SENT'` para el tope por hora |
| `rule_throttle` | Un disparo por regla, clave y ventana: `fired_at`, `until` | `pk (rule_key, dimension_key)`; upsert `... do update where rule_throttle.until <= now()`: una fila afectada = dispara |
| `source_cursor` | La marca del lector: `kind` (`KEYCLOAK_LOGIN`, `KEYCLOAK_ADMIN`, sembradas en la migración), `last_event_time`, `lease_owner`, `lease_until`, `last_poll_at`, `last_success_at`, `last_error` | Arrendamiento con `update ... where lease_until is null or lease_until < now()` (recuento de filas); la marca solo avanza (`greatest`) y solo la suelta quien la tiene |

Todo lo que cuelga de `notification` (audiencias, recibos, entregas) va `on delete cascade`: la
purga de notificaciones se lleva lo suyo. `activity_event` no tiene hijos que cuelguen de ella
salvo el `superseded_by` de otro evento y el `activity_event_id` de una notificación, los dos
`on delete set null`.
