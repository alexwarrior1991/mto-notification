# Realm de Keycloak para `mto-notification`

Estos ficheros son la definición de lo que `mto-notification` necesita en el servidor de identidad.
Se versionan para que la configuración de Keycloak se revise en pull request como cualquier otro
cambio, y para que los entornos no diverjan por lo que alguien pinchó un día en la consola.

La parcial no contiene ningún secreto: el de la cuenta de servicio `mto-notification-svc` lo genera
Keycloak al importar y se lee desde la consola. Solo el fichero de desarrollo lo fija a un valor
conocido, para que el stack local arranque sin copiar nada a mano.

## El realm es compartido

Los siete servicios del dominio viven en el **mismo realm** (`mto`). Se separa por entorno y no por
aplicación, porque los usuarios son los mismos y un realm compartido evita duplicar identidades. El
aislamiento entre servicios lo dan los roles de cliente.

El realm base lo crea y lo posee
[`mto-platform`](https://github.com/alexwarrior1991/mto-platform), y cada servicio aporta desde su
propio repositorio lo suyo. Este trae dos ficheros:

| Fichero | Qué aporta |
|---|---|
| `mto-notification-partial-import.json` | Los clientes `mto-notification-api` y `mto-notification-svc`, los permisos y los perfiles `mto-notification-*`. Vale para cualquier entorno. |
| `mto-notification-dev.json` | Los tres usuarios de desarrollo y el secreto fijo de la cuenta de servicio (`mto-notification-svc-secret`, el mismo que `MTO_NOTIFICATION_SERVICE_CLIENT_SECRET` en `mto-platform/.env.example`). Aparte a propósito, para poder aplicar lo anterior en un entorno desplegado sin arrastrarlos. |

Los aplica `mto-platform/keycloak/apply-partials.sh`, que fija el orden. **Esta parcial va la
primera**: los perfiles de los demás servicios (`mto-viewer`, `mto-warehouse-admin`,
`mto-maintenance-manager`, `mto-users-admin`...) nombran `notification-inbox` y los demás permisos
de aquí, y un compuesto solo puede nombrar roles de un cliente que ya exista en el realm.

## Qué hay dentro

### Clientes

| Cliente | Tipo | Para qué |
|---|---|---|
| `mto-notification-api` | Confidencial, sin flujos | Declara los permisos como roles de cliente y es la **audiencia** de los tokens que valida esta API. |
| `mto-notification-svc` | Cuenta de servicio (`client_credentials`) | Con ella `mto-notification` lee los eventos de acceso y de administración del realm (`/admin/realms/mto/events` y `/admin-events`) y resuelve las direcciones de correo de una audiencia. |

`mto-notification` no declara ningún cliente de navegador propio: usa el `mto-frontend` del realm
base de `mto-platform` y el `mto-backoffice` del backoffice, que llevan un *audience mapper* hacia
`mto-notification-api`.

### Permisos y perfiles

Los **permisos** son roles de cliente de `mto-notification-api` y son lo que comprueba el código
(`SecurityRoles`). Los **perfiles** son roles de realm compuestos que los agrupan, y son lo que se
asigna a las personas. A diferencia de sus hermanos, aquí el permiso no lo decide el verbo sino el
recurso, y ninguno implica a otro.

| Permiso | Concede |
|---|---|
| `notification-inbox` | `GET /inbox`, `GET /inbox/unread-count`, `POST /inbox/{id}/read`, `POST /inbox/read-all`: mi bandeja |
| `notification-activity-read` | `GET /activity/**`: el registro de actividad, todo menos los accesos |
| `notification-access-read` | `GET /access/**`: los accesos, con usuario e IP. Aparte a propósito: son datos personales |
| `notification-admin` | `/admin/**`: reglas cargadas, entregas y reintentos, estado de las fuentes, avisos manuales |
| `ops-metrics` | Lectura de los endpoints de Actuator |
| `ops-write` | Operaciones de Actuator que modifican estado |

| Perfil | Agrupa |
|---|---|
| `mto-notification-viewer` | `notification-inbox`, `notification-activity-read` |
| `mto-notification-auditor` | los del lector + `notification-access-read` |
| `mto-notification-admin` | los del auditor + `notification-admin` |

Los perfiles de los demás servicios llevan también permisos de aquí, en su propia parcial: todos
`notification-inbox` (la campana es de todo el mundo), los de responsable, auditor y ops
`notification-activity-read`, `mto-auditor` y `mto-users-admin` `notification-access-read`, y
`mto-ops` `notification-admin` (`mto-platform/keycloak/mto-ops-cross-service.json`). El responsable de campo (`mto-field-supervisor`, en la parcial de `mto-field`) lleva
`notification-inbox` y `notification-activity-read` desde la fase 5 de ese servicio, que es cuando
hay algo que recibir; el técnico de campo no lleva nada de aquí: un dispositivo solo habla con
`mto-field`.

### La cuenta de servicio

`mto-notification-svc` necesita seis roles de `realm-management`, que **no** van en la parcial (una
importación parcial no asigna roles a cuentas de servicio) y concede `apply-partials.sh` en local:

| Rol | Para qué |
|---|---|
| `view-events` | Leer `/events` y `/admin-events`: los accesos y las mutaciones del realm |
| `view-users`, `query-users` | Los miembros de un perfil y la dirección de un usuario, para el correo |
| `view-realm` | Los perfiles (roles de realm) y sus compuestos |
| `view-clients`, `query-clients` | Los roles de cliente y sus miembros directos |

Ni `manage-events` (nunca borra ni reconfigura los eventos), ni `manage-users`, ni `manage-realm`,
ni `realm-admin`. Nunca una credencial del realm `master`.

## Cómo cargarlo

```bash
cd ../mto-platform
docker compose up -d
./keycloak/apply-partials.sh                 # con los usuarios de desarrollo
./keycloak/apply-partials.sh --no-dev-users  # solo clientes, permisos y perfiles
```

Usuarios de desarrollo (contraseña `local`):

| Usuario | Perfil |
|---|---|
| `notificacion.lector` | `mto-notification-viewer` |
| `notificacion.auditor` | `mto-notification-auditor` |
| `notificacion.responsable` | `mto-notification-admin` |

A mano, sobre un realm que ya existe: **Realm settings → Partial import** con
`mto-notification-partial-import.json` y estrategia **Skip**, o por la API de administración
(`POST /admin/realms/mto/partialImport`) añadiendo `"ifResourceExists": "OVERWRITE"` para poder
reejecutar.

## Después de importar

1. **Conceder los roles de `realm-management` a la cuenta de servicio** (Clients →
   `mto-notification-svc` → Service accounts roles): los seis de la tabla de arriba. Sin
   `view-events` el lector de Keycloak recibe 403 y el registro de accesos se queda vacío.
2. **Activar los eventos del realm** (Realm settings → Events): los de usuario con la lista de tipos
   que documenta `mto-platform/keycloak/README` y los de administración con detalles. Sin ellos no
   hay nada que leer. En local lo hace `apply-partials.sh`.
3. **Copiar el secreto del cliente** `mto-notification-svc` (Clients → Credentials) a
   `KEYCLOAK_SERVICE_CLIENT_SECRET`. En local no hace falta: `mto-notification-dev.json` lo fija.
4. **Crear los usuarios y asignarles su perfil.** La parcial no trae ninguno; los de desarrollo están
   en `mto-notification-dev.json`.

## Lo que aportan los otros repositorios

- `mto-platform/keycloak/mto-realm.json` da a `mto-frontend` un *audience mapper*
  `audiencia-mto-notification-api`, y `mto-backoffice/keycloak/mto-backoffice-partial-import.json`
  otro igual a `mto-backoffice`. Sin él, un token puede llegar aquí sin `mto-notification-api` en
  `aud` y la API lo rechaza con 401.
- `mto-platform/keycloak/mto-ops-cross-service.json` redefine `mto-ops` con los `ops-*` de las seis
  APIs y con `notification-admin`. Se aplica **después** de esta parcial.

## Comprobar que quedó bien

`KeycloakAuthorizationIT` levanta un Keycloak real con un realm de la misma forma
(`src/test/resources/keycloak/mto-notification-test-realm.json`) y verifica contra él los permisos
por recurso, la expansión de los compuestos, que un rol de realm homónimo no concede el permiso y
la audiencia:

```bash
./mvnw verify -Dit.test=KeycloakAuthorizationIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

Necesita Docker. Sin Docker el test se salta en lugar de fallar.
