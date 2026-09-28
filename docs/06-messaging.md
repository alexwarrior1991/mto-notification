# 06 · Mensajería y fuentes

## Las fuentes

| Fuente | Transporte | Estado |
|---|---|---|
| Datos maestros de `mto-configuration` | `mto.master-data.exchange`, `mto.master-data.#` → cola propia `mto.notification.master-data.queue` | Fase 2a |
| Accesos y administración de Keycloak | Admin API, sondeo cada 20 s con marca de agua | Fase 2a |
| Trabajos de `mto-configuration` (`job.finished`) | `mto.configuration.exchange` → `mto.notification.configuration.queue` | Fase 2b/2d |
| `mto-users` | `mto.users.exchange` → `mto.notification.users.queue` | Fase 2c/2d |
| `mto-maintenance` | `mto.maintenance.exchange` → `mto.notification.maintenance.queue` | Fase 3 |
| `mto-stock` | `mto.stock.exchange` → `mto.notification.stock.queue` | Fase 4 |

Cada cola lleva su DLX/DLQ (`<cola>.dlx`, `<cola>.dlq`), como las de `mto-stock` y
`mto-maintenance`, y este servicio es su dueño; el exchange se declara en los dos lados con los
mismos argumentos porque es el contrato del productor. Las fuentes se configuran en
`app.rabbitmq.sources.<fuente>.*` y cada una tiene su `listener-enabled`; `app.rabbitmq.enabled=false`
arranca sin broker. La firma se comprueba sobre los bytes recibidos antes de nada
(`app.messaging.signature.mode`: `DISABLED`, `OPTIONAL`, `REQUIRED`; el secreto es el mismo que en
`mto-configuration`).

## El sobre

El de `mto-configuration` (`README_MESSAGING.md` de ese repositorio): `operationId`, `referenceId`,
`origin`, `creationDate`, `eventType`, `data{entityName, entityId, operation, values}`,
`messageHash`, con las cabeceras `messageSignature`, `messageSignatureAlgorithm`, `sequenceNumber`
y `traceparent`. Los productores nuevos añaden dos claves, `actor{id, username, kind}` y
`correlationId`; `SourceEnvelope` ya las lee y las tolera ausentes. Solo se añaden claves: el
contrato es de cada productor y este servicio lee lo que reconoce e ignora lo demás. Un mensaje sin
`data`, sin `entityName` o con una operación que no es `CREATED`/`UPDATED`/`DELETED` va a la DLQ.

## Inbox y reparto

`SourceEventConsumer` → `IdempotentSourceEventProcessor` → `InboxMessageService` →
`DispatchingSourceEventHandler` → el `ActivitySourceAdapter` de la fuente (`sourceId()`; dos con el
mismo id impiden arrancar). Idempotencia por `(message_id, source_service)` con el mismo
`inbox_message` que sus hermanos: `message_id` es el `operationId` del sobre (o el `message_id`
AMQP) y, para un evento de Keycloak, su huella SHA-256. El adaptador corre dentro de la transacción
del inbox; un fallo transitorio deja el mensaje `FAILED` con su motivo y lo devuelve al broker, uno
permanente (`UnprocessableSourceEventException`) lo manda a la DLQ.

### Datos maestros

`MasterDataSourceAdapter` decide entre línea directa y ráfaga: las bajas de vía, estación, perfil,
seccionador y aislador y el alta de un paquete de ejecución son una línea cada una
(`configuration.<entidad>.<operación>`, `WARNING` la baja), y lo demás entra en `activity_burst`
por `origen|entidad|operación|actor|correlationId`. Con el `correlationId = jobId` de la fase 2b,
una importación entera es **una** línea con `eventCount`. Las reglas: una ráfaga de 50 o más avisa
a `mto-maintenance-manager` y `mto-ops`; una baja de infraestructura, además por correo; un
paquete nuevo, a mantenimiento y almacén.

## El lector de Keycloak

Keycloak 26.1 devuelve los eventos siempre por `time` descendente, paginados (`first`/`max`), sin
`id` y con `dateFrom`/`dateTo` solo por día; fecha cada evento al construirlo y lo persiste al final
de la petición. Por eso:

1. `KeycloakEventsPoller` reclama un arrendamiento por fuente en `source_cursor` (`KEYCLOAK_LOGIN`,
   `KEYCLOAK_ADMIN`; `lease-ttl` 2 min): una instancia a la vez.
2. Pagina de lo más nuevo hacia atrás hasta cruzar `marca − overlap` (2 min), o hasta
   `max-pages-per-poll`; sin marca, `initial-lookback` (1 h). Lo anterior lo tiene Keycloak 7 días.
3. Procesa la pasada del más antiguo al más nuevo (una racha se detecta en el orden en que pasó):
   cada evento entra por el inbox con `message_id` = huella SHA-256 de sus campos (`time`, `type`,
   `realmId`, `clientId`, `userId`, `sessionId`, `ipAddress`, `error`, `details`), así que el
   solape no repite nada. Un evento envenenado queda `FAILED` en el inbox con su JSON y no bloquea
   la marca.
4. Suelta el arrendamiento avanzando la marca al `time` más nuevo visto (nunca hacia atrás) y, si
   falló, guarda el error sin mover la marca; más de diez intervalos sin una pasada buena registran
   `system.source.stalled`.

Todo el HTTP corre fuera de transacción, con la cuenta de servicio `mto-notification-svc`
(`client_credentials`, `view-events`) y el circuito `keycloak`.

| Evento de Keycloak | Tipo | Notas |
|---|---|---|
| `LOGIN`, `LOGOUT` | `access.login`, `access.logout` | `INFO` |
| `LOGIN_ERROR`, `LOGOUT_ERROR` | `access.login.failed`, `access.logout.failed` | `WARNING`, con `error` y `username` |
| `UPDATE_PASSWORD`, `RESET_PASSWORD`, `SEND_RESET_PASSWORD` | `access.password.changed`, `.reset`, `.reset-requested` | |
| `UPDATE_TOTP`, `REMOVE_TOTP`, `UPDATE_CREDENTIAL`, `REMOVE_CREDENTIAL` | `access.totp.*`, `access.credential.*` | |
| `USER_DISABLED_BY_TEMPORARY_LOCKOUT`, `..._PERMANENT_LOCKOUT` | `access.lockout` | `CRITICAL`, `payload.permanent` |
| `IMPERSONATE`, `UPDATE_PROFILE`, `UPDATE_EMAIL`, `EXECUTE_ACTIONS`, `EXECUTE_ACTION_TOKEN` | `access.impersonation`, `.profile.updated`, `.email.updated`, `.actions.executed` | |
| `CLIENT_LOGIN`, `CODE_TO_TOKEN`, `REFRESH_TOKEN`, `INTROSPECT_TOKEN`, `USER_INFO_REQUEST`... | _(ignorados)_ | Ruido de máquina, tampoco activados en el realm |
| Cualquier otro | `access.other` | |

Los eventos de administración se clasifican por `operationType`, `resourceType` y `resourcePath`:
`users.admin.user-created/updated/deleted`, `password-reset`, `logout`, `session-deleted`,
`credential-deleted`, `client-roles-added/removed`, `realm-roles-added/removed`,
`consent-revoked`, `actions-email-sent`, `other`. El actor es `SERVICE` cuando
`authDetails.clientId` es `mto-users-svc` (`app.keycloak.events.users-service-client-id`) y
`PERSON` en cualquier otro caso; la regla `users-change-outside-application` avisa de estos
últimos (consola, `kcadm`) a `mto-users-admin` y `mto-ops`, con freno de 5 min por actor.

## Las reglas de esta fase

| Regla | Dispara | A quién | Canales |
|---|---|---|---|
| `access-login-streak` | `access.login.streak` | `mto-users-admin`, `mto-ops` | bandeja, correo; freno 30 min por dimensión y valor |
| `access-lockout` | `access.lockout` | `mto-users-admin`, `mto-ops` | bandeja, correo |
| `access-impersonation` | `access.impersonation` | `mto-users-admin`, `mto-ops` | bandeja, correo |
| `access-password-reset` | `access.password.reset` | el propio usuario | bandeja |
| `configuration-large-burst` | `configuration.*` con `eventCount >= vars['large-burst-threshold']` (50) | `mto-maintenance-manager`, `mto-ops` | bandeja |
| `configuration-infrastructure-deleted` | bajas de vía, estación, perfil, seccionador, aislador | `mto-maintenance-manager` | bandeja, correo |
| `configuration-package-created` | `configuration.execution-package.created` | `mto-maintenance-manager`, `mto-warehouse-admin` | bandeja |
| `users-change-outside-application` | `users.admin.*` con `payload.clientId != vars['users-service-client-id']` | `mto-users-admin`, `mto-ops` | bandeja, correo; freno 5 min por actor |
| `system-source-stalled`, `system-delivery-dead` | los eventos del propio servicio | `mto-ops` | bandeja, correo |

Registro sin aviso: `access.login`, `access.logout`, las ráfagas pequeñas, los cambios hechos desde
`mto-users`.

## Correo

`EmailChannel` manda un correo por destinatario con asunto `[MTO][SEVERIDAD] título`, texto plano
y HTML escapado, y el enlace absoluto (`app.notification.email.link-base-url` + `link`). El
despachador (`app.notification.delivery.*`) corre cada 15 s y además justo después de confirmar
una notificación; reintenta con espera exponencial (30 s a 1 h, con jitter) hasta 8 intentos, y
no manda más de `max-per-hour` (20) correos a una misma dirección. En local el SMTP es el Mailpit
de `mto-platform` (http://localhost:8025).
