# 02 · Modelo de dominio

_Fase 1: esta página fija los conceptos; las entidades, sus columnas y sus reglas llegan en la
fase 2 con la primera migración._

## Evento de actividad

La unidad del registro. Es **append-only** e idempotente por `(source_service, source_event_id)`:
dos entregas del mismo evento dejan una sola línea.

| Campo | Qué |
|---|---|
| `sourceService` | Quién lo produjo: `mto-configuration`, `mto-maintenance`, `mto-stock`, `mto-users`, `keycloak-login`, `keycloak-admin`, `mto-notification` (derivados) |
| `sourceEventId` | El `operationId` del evento, la huella de un evento de Keycloak o la clave de un derivado |
| `category` | `ACCESS`, `USERS`, `CONFIGURATION`, `MAINTENANCE`, `STOCK`, `SYSTEM` |
| `type` | `<categoría>.<sujeto>.<evento>`: `maintenance.order.created`, `access.login.failed`... |
| `severity` | `INFO`, `WARNING`, `CRITICAL` |
| `occurredAt` / `recordedAt` | Cuándo pasó (hora del origen) y cuándo se guardó |
| `actor` | `kind` (`PERSON`, `SERVICE`, `SYSTEM`), `username`, `id` |
| `subject` | `type`, `id`, `label`: la entidad sobre la que pasó |
| `correlationId` | El `X-Correlation-Id` de la petición que lo causó, o el id del mensaje |
| `ipAddress` | Solo en `ACCESS` |
| `eventCount` | Más de uno en una ráfaga agregada |
| `payload` | Un subconjunto normalizado, por lista blanca, nunca un secreto |

## Regla

Vive en un YAML versionado. Casa un tipo de evento (exacto, lista o comodín final) con una
condición opcional, y dice la severidad, las **audiencias**, los **canales**, las plantillas del
título, el cuerpo y el enlace, y un **throttle** (uno por clave y ventana).

## Notificación y audiencia

Una notificación nace de una regla (o de un aviso manual) y lleva una o varias audiencias:
`USER:<usuario>`, `PROFILE:<rol de realm>`, `CLIENT_ROLE:<cliente>:<rol>`; más adelante `TEAM` y
`ZONE`. **A quién le toca se resuelve al leer, con el token**: sin expandir miembros al crearla.
Leída o no leída es un estado por persona (`receipt`), y «marcar todas» es una marca de agua.

## Entrega

Los canales que empujan (correo; push después) dejan una entrega por notificación, canal y
destinatario, con reintentos e idempotencia por esa terna. Las direcciones se resuelven fuera de la
transacción de ingesta, contra el directorio de Keycloak.
