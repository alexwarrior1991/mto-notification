-- La categoria RESOURCES del registro: lo que mto-resources cuenta del personal, los equipos y la
-- flota. ALTER TYPE ... ADD VALUE corre dentro de la transaccion de Flyway desde PostgreSQL 12; la
-- unica condicion es no usar el valor nuevo en esta misma migracion, y no se usa.
ALTER TYPE activity_category ADD VALUE IF NOT EXISTS 'RESOURCES';
