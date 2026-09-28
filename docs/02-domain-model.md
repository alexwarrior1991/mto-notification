# 02 · Modelo de dominio

El registro es **append-only** y todo lo demás se deriva de él. Lo que decide una carrera o una
idempotencia lo decide la base de datos dentro de la sentencia que escribe
([`03-database.md`](03-database.md)); lo que decide qué es un aviso lo decide una regla en YAML.

## Evento de actividad (`activity_event`)

La unidad del registro. Idempotente por `(source_service, source_event_id)`: dos entregas del mismo
evento dejan una sola línea, y `seq` es el orden de llegada.

| Campo | Qué |
|---|---|
| `sourceService` | Quién lo produjo: `mto-configuration`, `mto-maintenance`, `mto-stock`, `mto-users`, `keycloak-login`, `keycloak-admin`, `mto-notification` (los derivados) |
| `sourceEventId` | El `operationId` del evento, la huella SHA-256 de un evento de Keycloak (que no trae `id`) o la clave de un derivado (`streak:username:alice:<ms>`, `burst:<uuid>`, `delivery-dead:<uuid>`) |
| `category` | `ACCESS`, `USERS`, `CONFIGURATION`, `MAINTENANCE`, `STOCK`, `SYSTEM`; se deriva del tipo |
| `type` | `<categoría>.<sujeto>.<evento>`, del catálogo de `ActivityTypes`: `access.login.failed`, `configuration.profile.updated`, `users.admin.user-updated`... Una regla que nombre un tipo que no existe impide arrancar |
| `severity` | `INFO`, `WARNING`, `CRITICAL` |
| `occurredAt` / `recordedAt` | Cuándo pasó (hora del origen) y cuándo se guardó |
| `actor` | `kind` (`PERSON`, `SERVICE`, `SYSTEM`), `username`, `id`. Una cuenta de servicio es `service-account-<cliente>` |
| `subject` | `type`, `id`, `label`: la entidad sobre la que pasó |
| `correlationId` | El `X-Correlation-Id` de la petición que lo causó, o el id del trabajo que lo produjo |
| `ipAddress` | Solo en `ACCESS`; el `CHECK` del esquema lo garantiza y el borrador la descarta antes |
| `eventCount` | Más de uno en una ráfaga agregada |
| `payload` | Un subconjunto normalizado por lista blanca (`PayloadSanitizer`): nunca una clave que contenga `password`, `secret`, `token`, `credential`, `otp`, `authorization`, `cookie` o `apikey`, a cualquier profundidad |
| `supersededBy` | Reservado para fundir el evento de Keycloak con el de `mto-users` (fase 2d) |

El vocabulario completo, por fuente, está en [`06-messaging.md`](06-messaging.md).

### Derivados

- **Racha de accesos fallidos** (`access.login.streak`, `CRITICAL`): `FailedLoginStreakDetector`
  corre tras ingerir cada `access.login.failed`, en su misma transacción, y cuenta los fallos del
  mismo usuario **o** de la misma IP en la ventana (`app.notification.access.streak`: 3 en 10 min).
  Al llegar al umbral ingiere un evento con `source_event_id = streak:<dimensión>:<valor>:<inicio
  de la ventana en ms>`: el cuarto y el quinto fallo calculan la misma clave y no vuelven a
  disparar, y dos instancias tampoco.
- **Ráfaga de datos maestros** (`configuration.<entidad>.<created|updated>` con `eventCount`): ver
  abajo.
- **Fuente parada** (`system.source.stalled`) cuando el lector de Keycloak lleva más de diez
  intervalos sin una pasada buena, y **entrega muerta** (`system.delivery.dead`) cuando un correo
  agota sus intentos.

## Ráfaga (`activity_burst`)

Una importación de perfiles emite miles de eventos `profile` iguales. Cada uno hace un upsert en
la ráfaga abierta de su clave (`origen|entidad|operación|actor|correlationId`: contador,
`last_event_at` y una muestra de hasta 20 ids), y un planificador la cierra cuando lleva 30 s sin
eventos o 10 min abierta, escribiendo **una** línea con `event_count` y la muestra. El índice único
parcial sobre las abiertas es lo que hace que solo un cerrador gane y que el siguiente evento abra
otra. No se agregan las bajas de vía, estación, perfil, seccionador y aislador ni el alta de un
paquete de ejecución (`app.notification.burst.direct-*`): esas son una línea cada una.

## Regla (`notification-rules.yml`)

Vive en un YAML versionado con el código (`app.notification.rules-location` para sobrescribirlo
por entorno) y se valida al arrancar: clave repetida, tipo desconocido, audiencia o canal
desconocidos impiden arrancar.

| Clave | Qué |
|---|---|
| `key` | Identificador estable, en kebab-case; queda en la notificación (`rule_key`) |
| `event` / `events` | Tipo exacto, lista o comodín final (`configuration.*`) |
| `when` | SpEL de solo lectura sobre `event` (la línea), `payload` y `vars`; una clave que falta vale `null`, no rompe. Si falla, la regla se salta y se registra |
| `severity` | Si falta, la del evento |
| `audiences` | `KIND:clave`, con la clave como plantilla (`USER:#{event.actorUsername}`); una que queda en blanco se omite |
| `channels` | `inbox` (siempre implícito en la bandeja) y `email`; `push` más adelante |
| `title`, `body`, `link` | Plantillas `#{...}`; el enlace es una ruta del backoffice |
| `throttle` | `window` y `key` (plantilla): un disparo por regla, clave y ventana, decidido con un upsert en `rule_throttle` |

`variables` del YAML (sobrescribibles con `app.notification.rules.variables.*`) llegan a `when`
como `vars['nombre']`.

## Notificación, audiencia y bandeja

Una notificación nace de una regla (o de un aviso manual, `manual-broadcast`) y lleva una o varias
audiencias: `USER:<usuario>`, `PROFILE:<rol de realm>`, `CLIENT_ROLE:<cliente>:<rol>`; `TEAM` y
`ZONE` quedan reservados para `mto-field`. **A quién le toca se resuelve al leer, con el token**
(`CurrentUserService.getAudienceKeys()`: la persona, cada rol de `realm_access` y cada rol de
`resource_access`), sin expandir miembros al crearla. Leída o no leída es un estado por persona
(`notification_receipt`); «marcar todas» fija `inbox_state.all_read_until` a la **más reciente
visible**, no a «ahora», para que una notificación en vuelo con `created_at` anterior no nazca
leída. El recibo gana a la marca. El contador de no leídas está acotado (`{count, capped}`).

## Entrega (`delivery`)

Los canales que empujan (`email`; `push` después) dejan una fila por notificación, canal y
**audiencia** en la misma transacción que la notificación. El despachador, fuera de esa
transacción, reclama las que vencen (`for update skip locked`, con un tiempo de visibilidad),
expande cada audiencia en filas por **destinatario** con las direcciones del directorio de
Keycloak, envía, y marca `SENT`, reprograma con espera exponencial o, agotados los intentos,
`FAILED` (y registra `system.delivery.dead`). Sin dirección o por encima del tope de correos por
hora y dirección, `SKIPPED` con su motivo. Los índices únicos parciales por audiencia y por
destinatario son la idempotencia: reintentar no manda dos veces. Keycloak caído solo retrasa el
correo.
