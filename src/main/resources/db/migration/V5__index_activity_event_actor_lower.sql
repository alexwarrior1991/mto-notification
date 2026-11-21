-- El filtro por usuario del registro (/activity?actorUsername=) y de los accesos (/access?username=,
-- el enlace de los avisos de racha) compara sin distinguir mayusculas, lower(actor_username) = ?,
-- porque un acceso fallido guarda el usuario tal como se tecleo. idx_activity_event_actor_occurred es
-- sobre la columna tal cual y no sirve a esa expresion: cada busqueda recorria la categoria entera
-- (medido con 2.000.000 de lineas: 345 ms la pagina de un usuario con pocas lineas y 300 ms su
-- recuento; con este indice, 0,1 ms cada uno).
--
-- La segunda columna es seq DESC porque ese es el orden de las listas (el que mandan las dos
-- aplicaciones web y, desde este cambio, el de por defecto): la pagina sale del indice ya ordenada,
-- sin depender de cuantas lineas estime el planificador para ese usuario. idx_activity_event_actor_occurred
-- se queda: lo usan, con igualdad directa, el detector de rachas y el correlador.
--
-- Sin CONCURRENTLY: corre dentro de la transaccion de Flyway y la tabla es pequena hoy. Sobre un
-- registro ya grande, CREATE INDEX bloquea las escrituras mientras dura (unos 2 s por cada
-- 2.000.000 de lineas): crearlo antes a mano con
--   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_activity_event_actor_lower_seq
--       ON activity_event (lower(actor_username), seq DESC);
-- y esta migracion, por su IF NOT EXISTS, no hara nada.
CREATE INDEX IF NOT EXISTS idx_activity_event_actor_lower_seq
    ON activity_event (lower(actor_username), seq DESC);
