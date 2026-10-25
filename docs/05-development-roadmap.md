# 05 · Hoja de ruta

## Hecho

- **Fase 1**: esqueleto del servicio (seguridad por recurso, contrato de errores, correlación,
  OpenAPI, perfiles, imagen, CI), los ficheros del realm (`keycloak/`) y esta documentación. En
  `mto-platform`, la base, Mailpit, los eventos del realm y la cuenta de servicio; en
  `mto-gateway`, la ruta; en `mto-backoffice`, la audiencia.
- **Fase 2a**: el servicio. El modelo y `V1`, el inbox, la ingesta con lista blanca, la cola propia
  de datos maestros con ráfagas, el lector de Keycloak (accesos y administración, marca de agua,
  idempotencia por huella, rachas), las reglas en YAML con sus frenos, la bandeja por persona, el
  correo con entregas reintentables, la retención y la API de administración.
- **Fase 2b** (`mto-configuration`): `actor` y `correlationId` en el sobre (con el id del trabajo
  en los hilos de importación, para que una importación sea una ráfaga), el evento
  `configuration.job.finished`, las colas huérfanas fuera y los perfiles con sus permisos de
  notificación.
- **Fase 2c** (`mto-users`): el evento propio `mto.users.<entidad>.<evento>` con la persona, y
  los perfiles con sus permisos de notificación.
- **Fase 2d**: las colas de los trabajos de `mto-configuration` y de `mto-users`, sus adaptadores
  (`ConfigurationSourceAdapter`, `UsersSourceAdapter`) y sus reglas, el correlador que funde el
  evento de Keycloak con el de `mto-users` (`superseded_by`, en cualquier orden de llegada), la
  audiencia `USER_ID` y los ejemplos de cada productor como fixtures de contrato.
- **Fase 3a** (`mto-maintenance`): el outbox copiado de `mto-configuration`, un evento propio por
  cada hecho de mantenimiento (`mto.maintenance.<entidad>.<evento>`) con la persona y la
  correlación, el aviso diario de preventivos a vencer y los perfiles con sus permisos de
  notificación.
- **Fase 3b**: la cola de `mto-maintenance`, `MaintenanceSourceAdapter` y sus reglas (orden
  urgente, asignación a la persona, órdenes cerradas, defectos graves y críticos, inspecciones,
  turnos, material sin existencias o sin respuesta del almacén, activos desactivados, preventivos
  a vencer), con los dieciséis ejemplos del productor como fixtures de contrato.
- **Fase 4a** (`mto-stock`): el mismo outbox y cuatro eventos propios: el material cuyo disponible
  total cruza por debajo de su mínimo (con la fila del material bloqueada para que el cruce lo vea
  una sola operación), la reserva cancelada o liberada con quien la creó, el ajuste de inventario;
  y los perfiles de almacén con sus permisos de notificación.
- **Fase 4b**: la cola de `mto-stock`, `StockSourceAdapter` y sus reglas (bajo mínimo al almacén
  con correo y freno de un día por material, la reserva de mantenimiento tocada por otro al
  responsable de mantenimiento, el ajuste negativo grande al almacén), con los cuatro ejemplos del
  productor como fixtures de contrato.
- **Fase 5** (`mto-backoffice` y `mto-frontend`): la campana con el contador, la bandeja
  (`notificaciones`), el registro (`actividad`) y los accesos (`actividad/accesos`), a la par en las
  dos aplicaciones.
- **Fase 6** (`mto-resources`): la categoría `RESOURCES` (`V3`), la cola de `mto-resources`,
  `ResourcesSourceAdapter` (que no registra lo republicado, `republished: true`) y sus reglas: el
  equipo cambiado o desactivado a su jefe, el vehículo que deja de estar disponible a los
  responsables, y las cualificaciones, los documentos de vehículo y las calibraciones a punto de
  caducar o caducados (lo caducado, `CRITICAL` y por correo). El jefe del turno recibe también el
  aviso de su turno iniciado, cerrado o cancelado por otra persona (`maintenance-shift-team-leader`),
  y el punto de acceso a la vía (`track-access-point`) es una entidad más de los datos maestros. Los
  ejemplos de `mto-resources`, copiados como fixtures de contrato.
- **Hilos virtuales**: Tomcat, los `@Scheduled` y los listeners, con el pool de conexiones como
  límite (`DATABASE_POOL_MAX_SIZE`), y el circuito `keycloak` llamando en un hilo virtual
  (`KeycloakCircuitBreakerExecutorConfiguration`). Los `@Scheduled` corren en un pool de cuatro
  (`SchedulingConfiguration`), también con hilos virtuales, para que no se esperen unos a otros.
- **OpenShift**: la imagen corre con un UID cualquiera del grupo 0 (la prueba de humo del CI arranca
  con `--user 1000680000:0`), y las colas de las fuentes y sus DLQ se declaran `classic` por defecto
  o `quorum` con `app.rabbitmq.queues.type` (`ConsumerQueueProperties`, con `x-delivery-limit` 5),
  para un RabbitMQ en clúster. Con `classic` la declaración es exactamente la de antes.

## Siguiente

Lo que el diseño dejaba preparado y sigue pendiente:

- Canal **push**: una implementación más de `DeliveryChannel` y una tabla de dispositivos
  (`PUT/DELETE /devices/{token}`); el canal es un `varchar` validado por el registro, sin
  migración de enumerado.
- Destinatarios por **equipo** o **zona**: dos `kind` de audiencia reservados; su casado necesita
  un dato que no está en el token de este servicio (el equipo o la zona de la persona). El equipo
  viaja en el claim `groups` del token de `mto-frontend`, con grupos que se llaman como el código del
  equipo (`A`, `B`), así que `TEAM:` podría leer de ahí; `mto-field` liga primero por la dotación
  del turno y usa el grupo solo en los turnos sin dotación.
- Avisos hacia el campo (turno asignado, orden urgente en su zona): mismo vocabulario de eventos;
  solo falta el canal y la audiencia.

## `mto-field`

`mto-field` existe desde después de este roadmap (la consola en directo de una posesión de vía
nocturna, sobre gRPC: quién está conectado, qué tareas empiezan y acaban y la orden de desalojo
con acuse nominal) y desde su fase 5 es una fuente más de este servicio: publica desde su outbox lo
que pasa en una posesión (abierta y cerrada, el desalojo con sus acuses, el equipo que no acusa a
tiempo, la salida de vía de cada equipo) por `mto.field.exchange`, con la categoría `FIELD` (`V2`),
el adaptador `FieldSourceAdapter` y las reglas `field-*`, que avisan a `mto-field-supervisor` (que
desde entonces tiene bandeja) y a `mto-maintenance-manager` ([`06-messaging.md`](06-messaging.md)).
Lo que dejaba preparado para el campo (el canal push y las audiencias por equipo y zona) está en
*Siguiente*.
