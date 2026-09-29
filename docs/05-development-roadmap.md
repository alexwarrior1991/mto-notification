# 05 · Hoja de ruta

## Hecho

- **Fase 1**: esqueleto del servicio (seguridad por recurso, contrato de errores, correlación,
  OpenAPI, perfiles, imagen, CI), los ficheros del realm (`keycloak/`) y esta documentación.

## Siguiente

- **Fase 2**: el modelo y la primera migración, el inbox y el consumo de los datos maestros de
  `mto-configuration` (con ráfagas: una importación es una línea y un aviso), el lector de
  Keycloak (accesos y administración, con marca de agua e idempotencia por huella), las reglas en
  YAML, la bandeja, el correo por Mailpit, la retención. Después, los adaptadores y las reglas de
  cada fuente según sus productores publican: `mto-configuration` (actor y fin de trabajo),
  `mto-users`, `mto-maintenance`, `mto-stock`.
- **Backoffice**: la campana con el contador, la bandeja (`notificaciones`) y el registro
  (`actividad`).

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
