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
y `RESOURCES`, `V3`, con `ALTER TYPE ... ADD VALUE`, que corre dentro de la transacción de Flyway
porque la migración no usa el valor nuevo), `activity_severity` (`INFO`,
`WARNING`, `CRITICAL`), `actor_kind` (`PERSON`, `SERVICE`, `SYSTEM`), `activity_burst_status`
(`OPEN`, `CLOSED`), `delivery_scope` (`AUDIENCE`, `RECIPIENT`), `delivery_status` (`PENDING`,
`IN_PROGRESS`, `SENT`, `FAILED`, `SKIPPED`).

## `V1__create_notification_schema.sql`

| Tabla | Para qué | Lo que la sostiene |
|---|---|---|
| `inbox_message` | Copia del de `mto-maintenance`; sirve a RabbitMQ **y** al lector de Keycloak | `unique (message_id, source_service)`; `payload` es `json` (los bytes tal cual, para la firma); índices por estado y `received_at` (la purga) y por fuente y estado (la vista de administración y los reintentos del lector de Keycloak, que salen de `status = FAILED`, `processing_attempts` y `failed_at`); lo aplicado se purga a los 7 días y lo `FAILED` a los 30 |
| `activity_event` | El registro: `seq` (`identity`, orden de llegada), origen, `category`, `type`, `severity`, `occurred_at`, `recorded_at`, actor, sujeto, `correlation_id`, `ip_address inet`, `event_count`, `payload jsonb`, `superseded_by` | `unique (source_service, source_event_id)` = **la** idempotencia; `check` de IP solo en `ACCESS`, de la forma del tipo (`^[a-z0-9-]+(\.[a-z0-9-]+)+$`) y de `event_count >= 1`; índices `(category, occurred_at desc)`, `(type, occurred_at desc)`, `(actor_username, occurred_at desc)`, `(subject_type, subject_id, occurred_at desc)`, parcial `(ip_address, occurred_at desc) where category = 'ACCESS'`, `(category, recorded_at)` para la purga y, desde `V4`, parcial `(superseded_by) where superseded_by is not null` y, desde `V5`, de expresión `(lower(actor_username), seq desc)` (ver abajo) |
| `activity_burst` | Ráfagas de datos maestros: `burst_key`, `status`, entidad, operación, actor, `correlation_id`, `event_count`, `sample_ids jsonb`, `opened_at`, `last_event_at`, `closed_at` | Índice único parcial `(burst_key) where status = 'OPEN'`: destino del upsert y lo que hace que solo un cerrador gane; parciales por `last_event_at` de las abiertas y `closed_at` de las cerradas |
| `notification` | `rule_key` (`manual-broadcast`, `test-email` en las manuales), `activity_event_id` (`on delete set null`), `category`, `severity`, `title`, `body`, `link`, sujeto | `(created_at desc, id desc)`, `(activity_event_id)` |
| `notification_audience` | `kind` y `audience_key` (`USER:alice`, `USER_ID:<id de Keycloak>`, `PROFILE:mto-ops`, `CLIENT_ROLE:mto-stock-api:stock-read`), `created_at` desnormalizado | `pk (notification_id, audience_key)`; `(audience_key, created_at desc, notification_id desc)` para la bandeja con claves estrechas |
| `notification_receipt` | Leída por persona | `pk (notification_id, username)`; el recibo gana a la marca |
| `inbox_state` | «Marcar todas» en O(1): `all_read_until` por `username` | Upsert que solo avanza (`greatest`) |
| `delivery` | Entregas de los canales que empujan: `channel varchar`, `scope`, `audience_kind`/`audience_key` o `recipient`/`recipient_username`, `status`, `attempts`, `max_attempts`, `next_attempt_at`, `claimed_at`, `sent_at`, `last_error` | Únicos parciales `(notification_id, channel, audience_key) where scope = 'AUDIENCE'` y `(notification_id, channel, recipient) where scope = 'RECIPIENT'` = idempotencia por notificación, canal y destinatario; `check` de las columnas de cada `scope`; parcial `(next_attempt_at) where status in ('PENDING', 'IN_PROGRESS')` para el reclamo; `(channel, recipient, sent_at) where status = 'SENT'` para el tope por hora |
| `rule_throttle` | Un disparo por regla, clave y ventana: `fired_at`, `until` | `pk (rule_key, dimension_key)`; upsert `... do update where rule_throttle.until <= now()`: una fila afectada = dispara |
| `source_cursor` | La marca del lector: `kind` (`KEYCLOAK_LOGIN`, `KEYCLOAK_ADMIN`, sembradas en la migración), `last_event_time`, `lease_owner`, `lease_until`, `last_poll_at`, `last_success_at`, `last_error` | Arrendamiento con `update ... where lease_until is null or lease_until < now()` (recuento de filas); la marca solo avanza (`greatest`) y solo la suelta, la renueva o la mueve quien la tiene (`where lease_owner = :owner`) |

Todo lo que cuelga de `notification` (audiencias, recibos, entregas) va `on delete cascade`: la
purga de notificaciones se lleva lo suyo. `activity_event` no tiene hijos que cuelguen de ella
salvo el `superseded_by` de otro evento y el `activity_event_id` de una notificación, los dos
`on delete set null`.

## `V4__index_activity_event_superseded_by.sql`

`superseded_by` es una clave ajena de `activity_event` a sí misma con `on delete set null`, y
PostgreSQL no indexa una clave ajena. Al borrar una línea, el trigger de integridad busca las que
apuntan a ella (`update activity_event set superseded_by = null where superseded_by = $1`) y sin
índice recorre el registro entero **por cada fila borrada**: la purga nocturna, que borra de mil en
mil, se volvía cuadrática. Medido con 2.000.000 de líneas (100.000 fundidas): 380 ms por fila
borrada, más de seis minutos por lote de mil; con `idx_activity_event_superseded_by`, 16 ms el lote
entero. El índice es parcial (solo las líneas fundidas, unos 3 MB con esas cifras); las listas, que
filtran `superseded_by is null`, y la actualización condicional del correlador (por `id`) no cambian
de plan.

La migración usa `create index if not exists` dentro de la transacción de Flyway, sin
`concurrently`, porque la tabla es pequeña hoy. Sobre un registro ya grande ese `create index`
bloquea las escrituras mientras dura: antes de desplegar, crearlo a mano con
`create index concurrently if not exists idx_activity_event_superseded_by on activity_event
(superseded_by) where superseded_by is not null;` y la migración no hará nada.

Regla general: **una clave ajena hacia una tabla que se purga lleva su índice** (aquí también
`notification.activity_event_id`, y las tres que cuelgan de `notification`).

## `V5__index_activity_event_actor_lower.sql`

Los filtros de texto de las listas (`/activity`, `/access`) no distinguen mayúsculas en lo que se
pide. Hasta aquí todos comparaban `lower(columna) = ?`, y un índice sobre la columna tal cual no
sirve a esa expresión: cada búsqueda recorría la categoría entera. Qué se hizo con cada columna, y
por qué:

| Columna | Qué puede guardar | Cómo se compara | Índice que lo sirve |
|---|---|---|---|
| `type` | Siempre minúsculas: `chk_activity_event_type_shape` | `type = lower(?)` | `(type, occurred_at desc)` de `V1` |
| `subject_type` | Siempre minúsculas: es el `entityName` que normalizan `DomainEventReader` y `MasterDataSourceAdapter`, o un literal de este servicio (`user`, `session`, `realm-resource`, `job`, `source`, `delivery`, `email`, `broadcast`, `ip`) | `subject_type = lower(?)` | `(subject_type, subject_id, occurred_at desc)` de `V1` |
| `actor_username` | **Puede llevar mayúsculas**: un acceso fallido guarda el usuario tal como se tecleó (`details.username` de Keycloak), y el de una cuenta de servicio lleva su `clientId` | `lower(actor_username) = lower(?)`, como antes | `idx_activity_event_actor_lower_seq`, `(lower(actor_username), seq desc)`, nuevo |
| `source_service` | Pocos valores distintos | `lower(source_service) = lower(?)`, como antes | Ninguno: no lo merece |

Quien añada un adaptador que escriba un `subject_type` que no salga de `DomainEventReader` lo escribe
en minúsculas: el filtro ya no lo encontraría de otro modo.

La segunda columna del índice nuevo es `seq desc` porque ese es el orden de las listas (el de por
defecto desde este cambio, `docs/04-rest-api.md`): la página de un usuario sale del índice ya
ordenada. `(actor_username, occurred_at desc)` de `V1` se queda: lo usan, con igualdad directa, el
detector de rachas y el correlador. Medido en PostgreSQL 16 con 2.000.000 de líneas:

| Consulta | Antes | Después |
|---|---|---|
| `/access?username=` de un usuario con 40 accesos, página | 345 ms (712.481 filas descartadas) | 0,11 ms |
| su `count` | 298 ms (recorrido completo) | 0,10 ms |
| `/activity?actorUsername=` (5.000 líneas), página / `count` | 3,5 ms / 251 ms | 0,11 ms / 9 ms |
| `/activity?type=` de un tipo raro, página / `count` | 894 ms / 294 ms | 0,04 ms / 0,01 ms |
| `/activity?subjectType=&subjectId=`, página / `count` | 155 ms / 153 ms | 0,02 ms / 0,01 ms |
| `/activity` sin filtros ni `sort`, página | 245 ms (ordenar todo) | 0,03 ms (`seq`) |

Lo que sigue igual: `sort=occurredAt,desc` pedido a mano junto con `username` recorre la categoría
(369 ms con esas cifras), y el `count` de una lista sin filtros recorre todo lo que cumple.

Como `V4`, usa `create index if not exists` sin `concurrently`; sobre un registro ya grande (unos
2 s de bloqueo de escrituras por cada 2.000.000 de líneas, 60 MB) se crea antes a mano con
`create index concurrently if not exists idx_activity_event_actor_lower_seq on activity_event
(lower(actor_username), seq desc);`.

`idx_inbox_message_received_at` **no se quita**: parece redundante con `(status, received_at)`, pero
el planificador lo elige hoy para la purga de lo aplicado (casi todo el inbox está `PROCESSED`). Sin
él la purga usa `(status, received_at)` con el mismo tiempo (0,40 ms el lote de mil sobre 400.000
mensajes), así que quitarlo sería seguro; queda pendiente de decidirlo.
