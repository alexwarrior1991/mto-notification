-- superseded_by es una clave ajena de activity_event a si misma (ON DELETE SET NULL) y PostgreSQL no
-- indexa una clave ajena. Al borrar una linea, el trigger de integridad busca las que apuntan a ella
-- (update activity_event set superseded_by = null where superseded_by = $1) y, sin indice, recorre la
-- tabla entera una vez POR FILA BORRADA: la purga nocturna, que borra de mil en mil, se vuelve
-- cuadratica (medido con 2.000.000 de lineas: 380 ms por fila borrada, mas de 6 minutos por lote;
-- con el indice, 16 ms el lote entero).
--
-- Parcial: solo las lineas fundidas (las de Keycloak que deja atras un evento de mto-users), una
-- fraccion pequena del registro. La comparacion del trigger es estricta, asi que el planificador
-- sabe que implica IS NOT NULL y usa el indice. Las listas, que filtran superseded_by IS NULL, no lo
-- tocan.
--
-- Sin CONCURRENTLY: corre dentro de la transaccion de Flyway y la tabla es pequena hoy. Si alguna vez
-- se aplica sobre un registro ya grande, CREATE INDEX bloquea las escrituras mientras dura (segundos
-- con millones de filas: solo lee superseded_by): crearlo antes a mano con
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_activity_event_superseded_by
--       ON activity_event (superseded_by) WHERE superseded_by IS NOT NULL;
-- y esta migracion, por su IF NOT EXISTS, no hara nada.
CREATE INDEX IF NOT EXISTS idx_activity_event_superseded_by
    ON activity_event (superseded_by)
    WHERE superseded_by IS NOT NULL;
