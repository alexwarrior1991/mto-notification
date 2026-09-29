# 07 · Auditoría y datos personales

## Quién tocó una fila

`created_by`/`updated_by` y sus fechas (Spring Data JPA auditing) a través de `AuditActorResolver`:
el `preferred_username` del token, `system` para los procesos de fondo (el consumidor de datos
maestros, el lector de Keycloak, el cierre de ráfagas, el despachador, la purga) y `unknown` para
una petición HTTP que llega a escribir sin usuario en el contexto, que además deja un aviso en el
log. Las tablas que solo se escriben con SQL nativo (`inbox_message`, `activity_event`,
`activity_burst`, `delivery`, `rule_throttle`, `source_cursor`) ponen `system` en la sentencia. No
hay Envers: el registro es append-only y su historia es él mismo.

## Datos personales

- Los accesos llevan nombre de usuario e IP. Solo viven en la categoría `ACCESS`, que nunca sale
  por `/activity` y tiene su permiso (`notification-access-read`). El `CHECK` de `activity_event`
  impide una IP fuera de esa categoría, y el borrador la descarta antes de llegar a la base.
- Nunca contraseñas, tokens ni secretos: los detalles de un evento de Keycloak pasan por una lista
  blanca (`username`, `auth_method`, `error`, `reason`, `identity_provider`, `credential_type`,
  `impersonator`...), la representación de un evento de administración se descarta salvo los
  nombres de rol de un *role mapping*, y `PayloadSanitizer` tira cualquier clave que contenga
  `password`, `secret`, `token`, `credential`, `otp`, `authorization`, `cookie` o `apikey` a
  cualquier profundidad. El JSON crudo solo vive en el inbox, que se purga a los 7 días.
- Un correo lleva el título, el cuerpo y el enlace de la notificación; nunca el payload.

## Retención

`RetentionPurge` corre cada noche (`app.notification.retention.cron`, 03:17) por lotes de 1000
filas y como mucho 50 lotes por tabla y pasada, cada lote en su transacción:

| Qué | Cuánto |
|---|---|
| `activity_event` `ACCESS` y `SYSTEM` | 90 días desde `recorded_at` |
| `activity_event` `USERS`, `CONFIGURATION`, `MAINTENANCE`, `STOCK` | 400 días |
| `notification` (y con ella audiencias, recibos y entregas, en cascada) | 180 días |
| `inbox_message` procesados | 7 días |
| `activity_burst` cerradas, `rule_throttle` vencidos | 1 día |

Keycloak caduca los suyos a los 7 días (`eventsExpiration`, `adminEventsExpiration`): este
servicio es el archivo. Seudonimizar a una persona queda como siguiente paso.
