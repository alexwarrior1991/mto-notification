# 04 · API REST

Raíz `/api/v1/notifications`; el gateway publica `/api/notifications/**`. Todo pide un bearer del
realm `mto` con audiencia `mto-notification-api`. Swagger UI en `/swagger-ui.html`.

## Permisos por recurso

| Recurso | Permiso |
|---|---|
| `/inbox/**` (todos los verbos) | `notification-inbox` |
| `GET`/`HEAD /activity/**` | `notification-activity-read` |
| `GET`/`HEAD /access/**` | `notification-access-read` |
| `/admin/**` | `notification-admin` |
| Cualquier otra ruta bajo la raíz | denegada |
| `/actuator/**` salvo `health` e `info` | `ops-metrics` (lectura) / `ops-write` (`POST`, `DELETE`) |

## Mi bandeja

| Método y ruta | Respuesta |
|---|---|
| `GET /inbox?unread=&category=&severity=&from=&to=&page=&size=&sort=` | `PageResponse<InboxItemResponse>`: lo dirigido a mi usuario, mis perfiles o mis roles de cliente, más reciente primero; cada elemento con `read` y `readAt`. `sort` admite `createdAt`, `severity`, `category`, `title` |
| `GET /inbox/unread-count` | `{count, capped}`: con `capped=true` el contador vale «cap o más» (100 por defecto) |
| `POST /inbox/{id}/read` | La notificación leída; 404 `NTF-404` si no me va dirigida (el id no dice si existe) |
| `POST /inbox/read-all` | `{allReadUntil}`: hasta la más reciente visible, no hasta ahora |

`InboxItemResponse`: `id`, `ruleKey`, `category`, `severity`, `title`, `body`, `link` (ruta del
backoffice), `subjectType`, `subjectId`, `activityEventId`, `createdAt`, `read`, `readAt`.

## El registro

| Método y ruta | Respuesta |
|---|---|
| `GET /activity?category=&type=&actorUsername=&subjectType=&subjectId=&severity=&sourceService=&from=&to=&includeSuperseded=` | `PageResponse<ActivityEventResponse>` sin `payload`; `category=ACCESS` es 400 `VAL-001`. `sort` admite `occurredAt`, `recordedAt`, `severity`, `type`, `seq` |
| `GET /activity/{id}` | El evento con su `payload`; 404 `ACT-404` si no existe o es un acceso |
| `GET /access?username=&ipAddress=&type=&outcome=&from=&to=` | `PageResponse<AccessEventResponse>` con `username`, `userId`, `ipAddress` y `outcome` (`SUCCESS`/`FAILURE`: fallos, rachas, bloqueos). `ipAddress` es un literal IPv4 o IPv6, que se lee sin consultar el DNS (`InetAddress.ofLiteral`): un nombre, también uno de letras hexadecimales como `dead.beef`, o un cuarteto fuera de rango es 400 `VAL-001` |

`ActivityEventResponse`: `id`, `seq`, `sourceService`, `sourceEventId`, `category`, `type`,
`severity`, `occurredAt`, `recordedAt`, `actor{kind, username, id}`, `subject{type, id, label}`,
`correlationId`, `eventCount`, `payload`, `supersededBy`.

## Administración

| Método y ruta | Respuesta |
|---|---|
| `GET /admin/rules` | `{source, variables, rules[]}`: las reglas cargadas y de dónde |
| `GET /admin/deliveries?status=&channel=&notificationId=` | `PageResponse<DeliveryResponse>`; `sort` admite `createdAt`, `status`, `nextAttemptAt`, `channel`, `sentAt` |
| `POST /admin/deliveries/{id}/retry` | La entrega otra vez `PENDING`; 404 `DLV-404`; 409 `DLV-409` si no está `FAILED` ni `SKIPPED` |
| `GET /admin/sources` | Marcas y arrendamientos del lector de Keycloak (`cursors[]`), recuentos del inbox por fuente y estado, ráfagas abiertas y entregas por estado |
| `GET /admin/dead-letters` | `{enabled, queues[{source, queue, deadLetterQueue, messages}]}`: lo que guarda la DLQ de cada fuente (`messages: null` si el broker no tiene la cola). Con `app.rabbitmq.enabled=false`, `{enabled: false, queues: []}` |
| `GET /admin/dead-letters/{source}?max=` | `{source, deadLetterQueue, messages, peeked[{message, inbox}]}`: los primeros `max` mensajes (20; entre 1 y 100) de la DLQ, **que siguen en ella**. `message` trae la clave del inbox, el tipo, el exchange y la clave de enrutado originales, por qué lo descartó el broker (`deathReason`, `deathCount`, `deadLetteredAt`), su tamaño y `problem` (por qué no llegaría al inbox: la firma, un cuerpo que no es el sobre...); nunca el cuerpo. `inbox` es lo que el inbox sabe de él (`status`, `attempts`, `failedAt`, `failureReason`) o `null` |
| `POST /admin/dead-letters/{source}/reprocess?max=` | `{source, deadLetterQueue, requested, fetched, processed, duplicates, failed, remaining, stoppedBy, results[{messageId, eventType, routingKey, outcome, reason}]}`: pasa hasta `max` mensajes (20; entre 1 y 100) por el consumidor de la fuente, cada uno una vez. `PROCESSED` y `DUPLICATE` salen de la DLQ; `FAILED` se queda, con su motivo. `stoppedBy`: `EMPTY`, `MAX` o `TIME_BUDGET` (10 s) |
| `POST /admin/test-email {to}` | 202 con la notificación `system.test-email` creada para quien llama y una entrega directa a la dirección |
| `POST /admin/broadcasts {title, body, link, severity, audiences[], channels[]}` | 201 + `Location`; audiencias como `KIND:clave` (`USER`, `USER_ID`, `PROFILE`, `CLIENT_ROLE`); 422 `NTF-422` con una clase de audiencia o un canal desconocidos |

Las tres rutas de las DLQ responden 404 `SRC-404` con una fuente que no existe (las fuentes son las
claves de `app.rabbitmq.sources`: `master-data`, `configuration`, `users`, `maintenance`, `stock`,
`field`, `resources`), 409 `SRC-409` con el broker apagado o si la DLQ no existe en él, 503 `SRC-503`
si el broker no responde y 400 `VAL-001` con un `max` fuera de rango. Cómo funcionan y qué garantizan,
en [`06-messaging.md`](06-messaging.md), *Las DLQ*.

```bash
curl -s -X POST "http://localhost:8086/api/v1/notifications/admin/dead-letters/maintenance/reprocess?max=10" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{"source":"maintenance","deadLetterQueue":"mto.notification.maintenance.queue.dlq","requested":10,
 "fetched":3,"processed":1,"duplicates":1,"failed":1,"remaining":1,"stoppedBy":"EMPTY",
 "results":[
  {"messageId":"0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10","eventType":"MAINTENANCE_ORDER_CREATED",
   "routingKey":"mto.maintenance.order.created","outcome":"PROCESSED","reason":null},
  {"messageId":"0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b66","eventType":"MAINTENANCE_ORDER_CREATED",
   "routingKey":"mto.maintenance.order.created","outcome":"FAILED",
   "reason":"Source message was rejected because its signature does not match the bytes received (algorithm HMAC-SHA256): source=maintenance, messageId=..."},
  {"messageId":"0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b77","eventType":"MAINTENANCE_ORDER_CREATED",
   "routingKey":"mto.maintenance.order.created","outcome":"DUPLICATE","reason":null}]}
```

## Paginación

`{content, page: {number, size, totalElements, totalPages, first, last}}`, `page` y `size` como
parámetros (`size` como mucho 100), `sort=campo,asc`; un `sort` fuera de la lista del recurso es 400
`REQ-400` con `validationErrors[{field: "sort"}]`.

## Errores

```json
{"timestamp":"2026-09-28T10:46:00Z","status":404,"error":"NOT_FOUND",
 "message":"Notification with id ... was not found","path":"/api/v1/notifications/inbox/.../read",
 "method":"POST","errorCode":"NTF-404","correlationId":"corr-1","validationErrors":[]}
```

| Código | Cuándo |
|---|---|
| `REQ-VALIDATION`, `REQ-400`, `REQ-415`, `REQ-405` | Forma de la petición (cuerpo, parámetros, `sort`, un enumerado que no existe) |
| `AUTH-401`, `AUTH-403` | Sin token, sin permiso |
| `HTTP-404`, `NTF-404`, `ACT-404`, `DLV-404`, `SRC-404`, `APP-404` | No existe (una fuente de RabbitMQ que no está configurada), o no es visible para quien pregunta |
| `DLV-409`, `SRC-409`, `APP-409` | La entrega no admite el reintento; el broker está apagado o la DLQ no existe; una restricción de la base |
| `NTF-422`, `APP-422` | Bien formado, pero nombra algo que este servicio no resuelve |
| `NTF-503`, `SRC-503` | El directorio (Keycloak) o RabbitMQ no responden; transitorio |
| `VAL-001`, `BUS-001` | Una regla de negocio (`category=ACCESS` en `/activity`, una IP que no es un literal) |
| `APP-500` | Inesperado; el detalle queda en el log con el `correlationId` |
