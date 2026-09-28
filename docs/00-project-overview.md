# 00 · Visión general

## Para qué

Hoy nadie sabe qué pasa en el dominio salvo mirando logs sueltos: `mto-users` escribe una línea de
auditoría por mutación, `mto-configuration` publica eventos de datos maestros que solo `mto-stock` y
`mto-maintenance` consumen para lo suyo, `mto-maintenance` y `mto-stock` guardan historial pero no
publican nada, y Keycloak no tenía los eventos activados. No había un sitio donde consultar «qué
fue, quién, cuándo, desde qué servicio y sobre qué», ni forma de avisar a una persona de una orden
urgente, de un material bajo mínimo o de tres logins fallidos seguidos.

`mto-notification` es ese sitio:

- **Un registro de actividad** normalizado y append-only, alimentado por las fuentes que ya existen
  (los eventos de datos maestros, los eventos de acceso y de administración de Keycloak) y por las
  que se crean para ello (los outbox de `mto-maintenance` y `mto-stock`, el evento propio de
  `mto-users`). Se consulta con filtros y con permiso; los accesos, que llevan usuario e IP, con un
  permiso aparte.
- **Un motor de reglas** que decide qué merece un aviso, a quién (un usuario, un rol de cliente o un
  perfil; más adelante un equipo o una zona) y por qué canal: la bandeja de la aplicación, con
  leídas y no leídas por persona, y el correo para lo urgente; más adelante push para `mto-field`.

## Lo que vive en otro sitio, a propósito

| Asunto | Dueño | Cómo lo usa este servicio |
|---|---|---|
| Datos maestros de infraestructura | `mto-configuration` | Consumidos como eventos; aquí solo queda la línea del registro |
| Órdenes, defectos, turnos, materiales | `mto-maintenance` | Eventos de su outbox |
| Existencias, reservas, movimientos | `mto-stock` | Eventos de su outbox |
| Usuarios, roles, perfiles | `mto-users` y Keycloak | El evento propio de `mto-users` (con la persona) y los eventos de administración de Keycloak |
| Identidad y tokens | Keycloak (`mto-platform`) | Resource server; los permisos son roles de cliente de `mto-notification-api` |
| Enrutado público | `mto-gateway` | `/api/notifications/**` → `/api/v1/notifications/**` |
| La campana y las pantallas | `mto-backoffice` | Consume esta API con el token de la persona |

Aquí no se remodela nada de los hermanos: el registro guarda un subconjunto normalizado de cada
evento (lista blanca) y los ids del servicio de origen.
