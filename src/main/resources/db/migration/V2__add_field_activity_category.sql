-- La categoria FIELD del registro: lo que mto-field cuenta de una posesion de via (fase 5).
-- ALTER TYPE ... ADD VALUE corre dentro de la transaccion de Flyway desde PostgreSQL 12; la unica
-- condicion es no usar el valor nuevo en esta misma migracion, y no se usa.
ALTER TYPE activity_category ADD VALUE IF NOT EXISTS 'FIELD';
