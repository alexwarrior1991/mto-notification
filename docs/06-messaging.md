# 06 · Mensajería

_Fase 1: esta página fija el diseño; el código llega en la fase 2._

## Las fuentes

| Fuente | Transporte | Cola propia |
|---|---|---|
| Datos maestros de `mto-configuration` | `mto.master-data.exchange`, `mto.master-data.#` | `mto.notification.master-data.queue` |
| Trabajos de `mto-configuration` | `mto.configuration.exchange`, `mto.configuration.#` | `mto.notification.configuration.queue` |
| `mto-maintenance` | `mto.maintenance.exchange`, `mto.maintenance.#` | `mto.notification.maintenance.queue` |
| `mto-stock` | `mto.stock.exchange`, `mto.stock.#` | `mto.notification.stock.queue` |
| `mto-users` | `mto.users.exchange`, `mto.users.#` | `mto.notification.users.queue` |
| Accesos y administración de Keycloak | Admin API, sondeo cada 20 s con marca de agua | _(ninguna: entra por el inbox como los demás)_ |

Cada cola lleva su DLX/DLQ (`<cola>.dlx`, `<cola>.dlq`), como las de `mto-stock` y
`mto-maintenance`, y este servicio es su dueño. El exchange se declara en los dos lados con los
mismos argumentos: es el contrato del productor.

## El sobre

El de `mto-configuration` (`README_MESSAGING.md` de ese repositorio): `operationId`, `referenceId`,
`origin`, `creationDate`, `eventType`, `data`, `messageHash`, con las cabeceras `messageSignature`,
`messageSignatureAlgorithm`, `sequenceNumber` y `traceparent`. Los productores nuevos añaden dos
claves, `actor{id, username, kind}` y `correlationId`, y su `data` es
`{entityName, entityId, eventName, values}`. Solo se añaden claves: el contrato es de cada
productor y este servicio lee lo que reconoce e ignora lo demás.

## Inbox

Idempotencia por `(message_id, source_service)` con el mismo `inbox_message` que sus hermanos:
`message_id` es el `operationId` del sobre (o el `message_id` AMQP) y, para un evento de Keycloak,
su huella SHA-256. La firma se comprueba sobre los bytes recibidos antes de nada
(`app.messaging.signature.mode`: `DISABLED`, `OPTIONAL`, `REQUIRED`).

## El lector de Keycloak

Keycloak 26.1 devuelve los eventos siempre por `time` descendente, paginados, sin `id` y con
`dateFrom`/`dateTo` solo por día. El lector reclama un arrendamiento en `source_cursor`, pagina de
lo más nuevo hacia atrás hasta cruzar la marca de agua menos una ventana de solapamiento (un
evento se fecha al construirse y se persiste al final de la petición), mete cada evento en el inbox
por su huella y solo entonces avanza la marca. Todo el HTTP corre fuera de transacción.
