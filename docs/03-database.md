# 03 · Base de datos

Flyway sobre `src/main/resources/db/migration`; Hibernate en `ddl-auto: validate` en todos los
perfiles, así que un cambio de esquema es siempre una migración nueva.

## Convenciones

- Ids `uuid`, columnas `created_at`/`updated_at`/`created_by`/`updated_by` (`AuditableEntity`),
  tablas en snake_case, enumerados cerrados como tipos de PostgreSQL (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`),
  `jsonb` para los payloads normalizados (`@JdbcTypeCode(SqlTypes.JSON)`), `timestamptz` para los
  instantes, `inet` para las IP.
- Los conjuntos que crecen por configuración (tipos de audiencia, canales) son `varchar` validados
  por el registro Java, no enumerados de PostgreSQL: añadir uno no es un `ALTER TYPE`.
- Sin Envers: el registro es append-only y su historia es él mismo.

## Migraciones

| Migración | Qué |
|---|---|
| _(fase 2)_ `V1__init.sql` | `inbox_message`, `activity_event`, `activity_burst`, `notification`, `notification_audience`, `notification_receipt`, `inbox_state`, `delivery`, `rule_throttle`, `source_cursor` |

En la fase 1 no hay ninguna: la aplicación arranca contra una base vacía y Flyway solo crea su
tabla de historial.
