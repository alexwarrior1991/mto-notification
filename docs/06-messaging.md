# 06 · Mensajería y fuentes

## Las fuentes

| Fuente | Transporte | Estado |
|---|---|---|
| Datos maestros de `mto-configuration` | `mto.master-data.exchange`, `mto.master-data.#` → cola propia `mto.notification.master-data.queue` | Fase 2a |
| Accesos y administración de Keycloak | Admin API, sondeo cada 20 s con marca de agua | Fase 2a |
| Trabajos de `mto-configuration` (`job.finished`) | `mto.configuration.exchange`, `mto.configuration.#` → `mto.notification.configuration.queue` | Fase 2d |
| `mto-users` | `mto.users.exchange`, `mto.users.#` → `mto.notification.users.queue` | Fase 2d |
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
contrato es de cada productor y este servicio lee lo que reconoce e ignora lo demás. Los eventos
propios de un servicio (`mto.configuration.exchange`, `mto.users.exchange`) llevan `data` en la
forma `DomainEvent` (`entityName`, `entityId`, `eventName`, `values`), y el tipo de la línea sale
de ahí: `<categoría>.<entityName>.<eventName>`, igual que la clave de enrutado. Un mensaje sin
`data`, sin `entityName`, sin `eventName` (los eventos propios) o con una operación que no es
`CREATED`/`UPDATED`/`DELETED` (los datos maestros) va a la DLQ. Los ejemplos que cada productor
versiona en su repositorio (`docs/messaging/examples/`) están copiados tal cual en
`src/test/resources/contracts/<productor>/` y son lo que los tests hacen pasar por los adaptadores.

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

### Trabajos de `mto-configuration`

`ConfigurationSourceAdapter` escucha `mto.configuration.#`. Hoy llega un evento, `job.finished`
(`README_ASYNC_JOBS.md` §12 y `README_MESSAGING.md` §2.6 de ese repositorio): una línea
`configuration.job.finished` por trabajo, con `jobId`, `type`, `status`, `createdBy`, las fechas,
los recuentos (`totalItems`, `processedItems`, `successfulItems`, `failedItems`), `fileName`,
`trackId` y `mapperType` en el payload (el detalle de errores por elemento no viaja: para eso está
el trabajo, al que el aviso enlaza). `occurredAt` es `finishedAt`; el sujeto, `job`/`jobId` con el
tipo como etiqueta; `WARNING` si acabó `FAILED` o `COMPLETED_WITH_ERRORS`. El actor es quien lo
lanzó (el sobre lo trae; si no, `createdBy`), y la correlación es el propio `jobId`, la misma con la
que los datos maestros de esa importación forman **una** ráfaga. Un evento de ese exchange que este
servicio aún no conoce se registra igualmente con su tipo y sin valores.

### `mto-users`

`UsersSourceAdapter` escucha `mto.users.#` (`README.md` de `mto-users`, *Eventos hacia
mto-notification*): una línea por acción administrativa, con la persona que la hizo, y el tipo
sale del nombre del evento: `users.user.created/updated/enabled/disabled/deleted`,
`users.user.password-reset`, `users.user.actions-email-sent`, `users.session.revoked/all-revoked`,
`users.offline-session.revoked/all-revoked`, `users.credential.deleted`,
`users.client-roles.added/removed`, `users.profile.assigned/removed` (`users.admin.*` es de los
eventos de Keycloak y un mensaje que lo use va a la DLQ). El sujeto es siempre el usuario objetivo
(`user`/`targetUserId`, con `targetUsername` de etiqueta cuando `mto-users` lo sabe: en roles,
perfiles y sesiones no lo sabe, y por eso las reglas avisan a la persona afectada por su id,
`USER_ID`). Del `values` se guardan `enabled`, `requiredActions`, `fields`, `temporary`,
`actions`, `clientId`, `client`, `roles`, `profile`, `session`, `sessions` y `type`;
`temporaryCredential` se guarda como `temporaryAccess` y el id de una credencial retirada no se
guarda, porque el saneador del registro tira cualquier clave que suene a credencial. Bajas,
desactivaciones, contraseñas, credenciales y sesiones son `WARNING`.

### El correlador de usuarios

Un cambio hecho desde `mto-users` llega dos veces: por su evento, con la persona, y por el evento de
administración de Keycloak, con la cuenta de servicio `mto-users-svc`. `UsersChangeCorrelator` es
un `DerivedEventDetector`: tras ingerir cada línea `USERS`, busca su pareja —mismo usuario objetivo
(o misma sesión: Keycloak nombra la sesión y `mto-users` la lleva en `payload.session`), acción
equivalente y a menos de `app.notification.users.correlation-window` (2 min) una de otra— y marca
la de Keycloak con `superseded_by` apuntando a la de `mto-users`, **en cualquier orden de
llegada** (lo habitual es que la de `mto-users` llegue primero: el lector sondea cada 20 s). La
marca es una actualización condicional (`where superseded_by is null`), así que dos instancias no
se pisan. `GET /activity` esconde lo fundido salvo `includeSuperseded=true`. Un evento de Keycloak
con actor `PERSON` (la consola, `kcadm`) nunca se funde: es justo lo que la regla
`users-change-outside-application` enseña.

| Keycloak (`users.admin.*`) | `mto-users` |
|---|---|
| `user-created` | `users.user.created` |
| `user-updated` | `users.user.updated`, `users.user.enabled`, `users.user.disabled` |
| `user-deleted` | `users.user.deleted` |
| `password-reset` | `users.user.password-reset` |
| `actions-email-sent` | `users.user.actions-email-sent` |
| `logout` | `users.session.all-revoked` |
| `session-deleted` (sujeto `session`) | `users.session.revoked`, `users.offline-session.revoked` (por `payload.session`) |
| `consent-revoked` | `users.offline-session.all-revoked` (uno por cliente, todos fundidos) |
| `credential-deleted` | `users.credential.deleted` |
| `client-roles-added/removed` | `users.client-roles.added/removed` |
| `realm-roles-added/removed` | `users.profile.assigned/removed` |

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
`consent-revoked`, `actions-email-sent`, `other`. `authDetails.clientId` trae el **id interno** del
cliente (un UUID), no su `clientId`: el adaptador lo resuelve en el directorio del realm
(`GET /clients/{id}`, recordado sin caducidad) y un cliente que no está en el realm (la consola de
`master`, `kcadm`) se queda con su UUID; `payload.authRealmId` dice de qué realm vino la
credencial. El actor es `SERVICE` cuando el cliente resuelto es `mto-users-svc`
(`app.keycloak.events.users-service-client-id`) y `PERSON` en cualquier otro caso; la regla
`users-change-outside-application` avisa de estos últimos (consola, `kcadm`) a `mto-users-admin` y
`mto-ops`, con freno de 5 min por actor.

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
| `configuration-job-finished` | `configuration.job.finished` | quien lanzó el trabajo (`USER:#{payload.createdBy}`) | bandeja; `WARNING` si acabó `FAILED` o con errores |
| `users-user-created`, `users-user-disabled` | `users.user.created`, `users.user.disabled` | `mto-users-admin` | bandeja |
| `users-user-deleted` | `users.user.deleted` | `mto-users-admin` | bandeja, correo |
| `users-credential-deleted` | `users.credential.deleted` | `mto-users-admin` y la persona (`USER_ID`) | bandeja |
| `users-sessions-revoked` | las cuatro de sesiones | `mto-users-admin` | bandeja; freno 5 min por usuario objetivo («sacar a la persona» son tres llamadas y un aviso) |
| `users-profile-changed`, `users-client-roles-changed` | perfiles y roles de cliente | `mto-users-admin` y la persona (`USER_ID`) | bandeja |
| `users-password-reset` | `users.user.password-reset` | la persona (`USER_ID`) | bandeja |
| `users-change-outside-application` | `users.admin.*` con `payload.clientId != vars['users-service-client-id']` | `mto-users-admin`, `mto-ops` | bandeja, correo; freno 5 min por actor |
| `system-source-stalled`, `system-delivery-dead` | los eventos del propio servicio | `mto-ops` | bandeja, correo |

Registro sin aviso: `access.login`, `access.logout`, las ráfagas pequeñas, `users.user.updated`,
`users.user.enabled`, `users.user.actions-email-sent`, y los eventos de administración de Keycloak
de un cambio hecho desde `mto-users` (fundidos con el de `mto-users`, que es el que avisa).

## Correo

`EmailChannel` manda un correo por destinatario con asunto `[MTO][SEVERIDAD] título`, texto plano
y HTML escapado, y el enlace absoluto (`app.notification.email.link-base-url` + `link`). El
despachador (`app.notification.delivery.*`) corre cada 15 s y además justo después de confirmar
una notificación; reintenta con espera exponencial (30 s a 1 h, con jitter) hasta 8 intentos, y
no manda más de `max-per-hour` (20) correos a una misma dirección. En local el SMTP es el Mailpit
de `mto-platform` (http://localhost:8025).
