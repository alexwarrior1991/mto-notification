# 04 · API REST

Raíz `/api/v1/notifications`; el gateway publica `/api/notifications/**`. Todo pide un bearer del
realm `mto` con audiencia `mto-notification-api`.

## Permisos por recurso

| Recurso | Permiso |
|---|---|
| `/inbox/**` (todos los verbos) | `notification-inbox` |
| `GET`/`HEAD /activity/**` | `notification-activity-read` |
| `GET`/`HEAD /access/**` | `notification-access-read` |
| `/admin/**` | `notification-admin` |
| Cualquier otra ruta bajo la raíz | denegada |
| `/actuator/**` salvo `health` e `info` | `ops-metrics` (lectura) / `ops-write` (`POST`, `DELETE`) |

## Recursos (fase 2)

| Método y ruta | Respuesta |
|---|---|
| `GET /inbox?unread=&category=&severity=&from=&to=&page=&size=` | `PageResponse<InboxItemResponse>` (mías, más reciente primero, con `read`) |
| `GET /inbox/unread-count` | `{count, capped}` |
| `POST /inbox/{id}/read` · `POST /inbox/read-all` | `InboxItemResponse` · `{allReadUntil}` |
| `GET /activity?category=&type=&actorUsername=&subjectType=&subjectId=&severity=&sourceService=&from=&to=` | `PageResponse<ActivityEventResponse>`; `category=ACCESS` es 400 |
| `GET /activity/{id}` | detalle con `payload` |
| `GET /access?username=&ipAddress=&type=&outcome=&from=&to=` | `PageResponse<AccessEventResponse>` |
| `GET /admin/rules` · `GET /admin/deliveries` · `POST /admin/deliveries/{id}/retry` · `GET /admin/sources` · `POST /admin/test-email` · `POST /admin/broadcasts` | administración |

## Paginación

`{content, page: {number, size, totalElements, totalPages, first, last}}`, `page` y `size` como
parámetros, `sort=campo,asc`; un `sort` desconocido es 400 `REQ-400`.

## Errores

```json
{"timestamp":"2026-09-28T10:46:00Z","status":404,"error":"NOT_FOUND",
 "message":"Notification with id ... was not found","path":"/api/v1/notifications/inbox/.../read",
 "method":"POST","errorCode":"NTF-404","correlationId":"corr-1","validationErrors":[]}
```

| Código | Cuándo |
|---|---|
| `REQ-VALIDATION`, `REQ-400`, `REQ-415`, `REQ-405` | Forma de la petición |
| `AUTH-401`, `AUTH-403` | Sin token, sin permiso |
| `HTTP-404`, `NTF-404`, `ACT-404`, `DLV-404`, `APP-404` | No existe, o no es visible para quien pregunta |
| `APP-409` | Una restricción de la base |
| `VAL-001`, `BUS-001` | Una regla de negocio |
| `APP-500` | Inesperado; el detalle queda en el log con el `correlationId` |
