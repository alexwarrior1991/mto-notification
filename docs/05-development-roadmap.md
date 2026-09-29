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

## Siguiente

- **Fase 4**: el outbox de `mto-stock` y su adaptador y sus reglas.
- **Fase 5** (`mto-backoffice`): la campana con el contador, la bandeja (`notificaciones`) y el
  registro (`actividad`).

## Más adelante: `mto-field`

La aplicación de campo para los técnicos. No se construye todavía, pero el diseño la deja preparada:

- Canal **push**: una implementación más de `DeliveryChannel` y una tabla de dispositivos
  (`PUT/DELETE /devices/{token}`); el canal es un `varchar` validado por el registro, sin
  migración de enumerado.
- Destinatarios por **equipo** o **zona**: dos `kind` de audiencia reservados; su casado necesita
  un dato que no está en el token (el equipo o la zona de la persona), que se resolverá con un
  atributo de usuario de Keycloak.
- Avisos hacia el campo (turno asignado, orden urgente en su zona) y desde el campo (tarea
  completada, defecto con foto): mismo vocabulario de eventos; solo falta el productor.
