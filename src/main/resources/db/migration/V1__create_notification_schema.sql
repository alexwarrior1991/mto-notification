-- Esquema de mto-notification: el registro de actividad y las notificaciones del dominio.
--
-- Todo lo que aqui se escribe con SQL nativo decide en la propia sentencia (on conflict, where con
-- recuento de filas, for update skip locked): nunca leer-y-escribir. La idempotencia es de la base,
-- no del codigo: el inbox por (message_id, source_service) y el registro por
-- (source_service, source_event_id).

-- ---------------------------------------------------------------------------------------------
-- Enumerados cerrados. Los abiertos (la clase de audiencia, el canal de entrega) son varchar
-- validados por el codigo: un TEAM o un push nuevo no puede exigir un ALTER TYPE.
-- ---------------------------------------------------------------------------------------------

CREATE TYPE inbox_message_status AS ENUM ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED');
CREATE TYPE activity_category AS ENUM ('ACCESS', 'USERS', 'CONFIGURATION', 'MAINTENANCE', 'STOCK', 'SYSTEM');
CREATE TYPE activity_severity AS ENUM ('INFO', 'WARNING', 'CRITICAL');
CREATE TYPE actor_kind AS ENUM ('PERSON', 'SERVICE', 'SYSTEM');
CREATE TYPE activity_burst_status AS ENUM ('OPEN', 'CLOSED');
CREATE TYPE delivery_scope AS ENUM ('AUDIENCE', 'RECIPIENT');
CREATE TYPE delivery_status AS ENUM ('PENDING', 'IN_PROGRESS', 'SENT', 'FAILED', 'SKIPPED');

-- ---------------------------------------------------------------------------------------------
-- Inbox: copia del de mto-maintenance. Sirve a RabbitMQ Y al lector de Keycloak, que entra por
-- aqui con la huella del evento como message_id y 'keycloak-login' / 'keycloak-admin' como origen.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE inbox_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Clave de idempotencia: el operationId del sobre (o el message_id de AMQP), o la huella
    -- SHA-256 de un evento de Keycloak. No se trunca nunca.
    message_id varchar(200) NOT NULL,
    source_service varchar(100) NOT NULL,
    event_type varchar(150),
    aggregate_type varchar(150),
    aggregate_id varchar(100),
    exchange_name varchar(255),
    routing_key varchar(255),
    queue_name varchar(255),
    payload_hash varchar(64),
    -- 'json' y no 'jsonb': se guarda lo que llego, caracter a caracter, y su hash tiene que seguir
    -- cuadrando. Es el unico sitio donde vive el JSON crudo de una fuente, y se purga a los 7 dias.
    payload json NOT NULL,
    status inbox_message_status NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    failed_at timestamptz,
    failure_reason text,
    processing_attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    CONSTRAINT uq_inbox_message_message_id_source UNIQUE (message_id, source_service),
    CONSTRAINT chk_inbox_message_processing_attempts_non_negative CHECK (processing_attempts >= 0),
    CONSTRAINT chk_inbox_message_processed_at_present_when_processed CHECK (
        status <> 'PROCESSED' OR processed_at IS NOT NULL
    ),
    CONSTRAINT chk_inbox_message_failed_at_present_when_failed CHECK (
        status <> 'FAILED' OR failed_at IS NOT NULL
    )
);

CREATE INDEX idx_inbox_message_status_received_at ON inbox_message (status, received_at);
CREATE INDEX idx_inbox_message_received_at ON inbox_message (received_at);
CREATE INDEX idx_inbox_message_source_status ON inbox_message (source_service, status);

-- ---------------------------------------------------------------------------------------------
-- El registro de actividad. Append-only: su historia es el mismo, y por eso no hay Envers.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE activity_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Orden de llegada, sin huecos que importen: es lo que pagina la bandeja de forma estable.
    seq bigint GENERATED ALWAYS AS IDENTITY,
    -- La idempotencia del registro: cada fuente identifica sus eventos y una repeticion no
    -- inserta nada. mto-notification es fuente de si mismo para los derivados (rachas, rafagas).
    source_service varchar(100) NOT NULL,
    source_event_id varchar(200) NOT NULL,
    category activity_category NOT NULL,
    -- <categoria>.<sujeto>.<evento>, en minusculas: 'access.login.failed'.
    type varchar(150) NOT NULL,
    severity activity_severity NOT NULL,
    -- Cuando paso, segun la fuente; recorded_at es cuando lo supo este servicio.
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    actor_kind actor_kind NOT NULL,
    actor_username varchar(255),
    actor_id varchar(100),
    subject_type varchar(100),
    subject_id varchar(200),
    subject_label varchar(255),
    correlation_id varchar(64),
    -- Solo los accesos llevan IP: es dato personal y tiene su permiso aparte.
    ip_address inet,
    -- Una rafaga resume varios eventos de la fuente en una linea: aqui va cuantos.
    event_count integer NOT NULL DEFAULT 1,
    -- Lo que la fuente dijo, ya pasado por lista blanca: nunca una contrasena ni un token.
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- El evento de Keycloak que un evento propio de mto-users deja obsoleto (fase 2d).
    superseded_by uuid REFERENCES activity_event (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    CONSTRAINT uq_activity_event_source UNIQUE (source_service, source_event_id),
    CONSTRAINT uq_activity_event_seq UNIQUE (seq),
    CONSTRAINT chk_activity_event_ip_only_for_access CHECK (ip_address IS NULL OR category = 'ACCESS'),
    CONSTRAINT chk_activity_event_type_shape CHECK (type ~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$'),
    CONSTRAINT chk_activity_event_count_positive CHECK (event_count >= 1)
);

CREATE INDEX idx_activity_event_category_occurred ON activity_event (category, occurred_at DESC);
CREATE INDEX idx_activity_event_type_occurred ON activity_event (type, occurred_at DESC);
CREATE INDEX idx_activity_event_actor_occurred ON activity_event (actor_username, occurred_at DESC);
CREATE INDEX idx_activity_event_subject_occurred ON activity_event (subject_type, subject_id, occurred_at DESC);
CREATE INDEX idx_activity_event_ip_occurred ON activity_event (ip_address, occurred_at DESC) WHERE category = 'ACCESS';
CREATE INDEX idx_activity_event_category_recorded ON activity_event (category, recorded_at);

-- ---------------------------------------------------------------------------------------------
-- Rafagas: una importacion de 12.645 perfiles es UNA linea del registro, no 12.645. Cada evento
-- de datos maestros hace un upsert sobre la rafaga abierta de su clave; un planificador cierra las
-- que llevan un rato sin recibir nada, o demasiado tiempo abiertas, y escribe la linea.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE activity_burst (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- origen|entidad|operacion|actor|correlationId
    burst_key varchar(500) NOT NULL,
    status activity_burst_status NOT NULL DEFAULT 'OPEN',
    source_service varchar(100) NOT NULL,
    entity_name varchar(150) NOT NULL,
    operation varchar(50) NOT NULL,
    actor_kind actor_kind NOT NULL,
    actor_username varchar(255),
    actor_id varchar(100),
    correlation_id varchar(64),
    event_count integer NOT NULL DEFAULT 1,
    -- Hasta N identificadores de las entidades tocadas, como muestra.
    sample_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    opened_at timestamptz NOT NULL DEFAULT now(),
    last_event_at timestamptz NOT NULL DEFAULT now(),
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    CONSTRAINT chk_activity_burst_count_positive CHECK (event_count >= 1),
    CONSTRAINT chk_activity_burst_closed_at_when_closed CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL)
);

-- El destino del upsert y lo que cierra la carrera: solo puede haber una rafaga ABIERTA por clave.
-- Un consumidor que esperaba en la fila bloqueada por el cerrador la encuentra cerrada y abre otra.
CREATE UNIQUE INDEX ux_activity_burst_open_key ON activity_burst (burst_key) WHERE status = 'OPEN';
CREATE INDEX idx_activity_burst_open_last_event ON activity_burst (last_event_at) WHERE status = 'OPEN';
CREATE INDEX idx_activity_burst_closed_at ON activity_burst (closed_at) WHERE status = 'CLOSED';

-- ---------------------------------------------------------------------------------------------
-- Notificaciones: lo que una regla decidio que merecia un aviso, a quien y por que canal.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- La regla que la genero; 'manual-broadcast' en los avisos manuales.
    rule_key varchar(100) NOT NULL,
    activity_event_id uuid REFERENCES activity_event (id) ON DELETE SET NULL,
    category activity_category NOT NULL,
    severity activity_severity NOT NULL,
    title varchar(255) NOT NULL,
    body text,
    -- Ruta relativa del backoffice; el correo la hace absoluta con link-base-url.
    link varchar(500),
    subject_type varchar(100),
    subject_id varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system'
);

CREATE INDEX idx_notification_created ON notification (created_at DESC, id DESC);
CREATE INDEX idx_notification_activity_event ON notification (activity_event_id);

-- A quien va. No se expanden miembros al crear: la bandeja se resuelve al leer, con las claves
-- que trae el token de la persona (USER:<usuario>, PROFILE:<rol de realm>,
-- CLIENT_ROLE:<cliente>:<rol>). 'kind' y 'audience_key' son varchar validados por el codigo.
CREATE TABLE notification_audience (
    notification_id uuid NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    kind varchar(30) NOT NULL,
    -- '<KIND>:<clave>', tal cual la calcula el token: es lo que se compara al leer.
    audience_key varchar(300) NOT NULL,
    -- Desnormalizado de notification.created_at para que el indice de abajo sirva a la bandeja
    -- de una audiencia estrecha sin pasar por la notificacion.
    created_at timestamptz NOT NULL,
    PRIMARY KEY (notification_id, audience_key)
);

CREATE INDEX idx_notification_audience_key_created ON notification_audience (audience_key, created_at DESC, notification_id DESC);

-- Leida, por persona. Gana a inbox_state.all_read_until.
CREATE TABLE notification_receipt (
    notification_id uuid NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    username varchar(255) NOT NULL,
    read_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (notification_id, username)
);

CREATE INDEX idx_notification_receipt_username ON notification_receipt (username);

-- 'Marcar todas como leidas' en O(1): todo lo creado hasta ahi esta leido para esa persona.
CREATE TABLE inbox_state (
    username varchar(255) PRIMARY KEY,
    all_read_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------
-- Entregas por los canales que empujan (correo hoy; push manana). AUDIENCE la escribe la ingesta,
-- una por (notificacion, canal, audiencia); el despachador la expande fuera de la transaccion de
-- ingesta en filas RECIPIENT, una por persona con direccion, y esas son las que se envian.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    -- 'email' hoy; varchar validado por el registro de canales.
    channel varchar(30) NOT NULL,
    scope delivery_scope NOT NULL,
    audience_kind varchar(30),
    audience_key varchar(300),
    -- La direccion del canal (el correo); el usuario, para poder leer a quien fue.
    recipient varchar(320),
    recipient_username varchar(255),
    status delivery_status NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_at timestamptz,
    sent_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    CONSTRAINT chk_delivery_scope_columns CHECK (
        (scope = 'AUDIENCE' AND audience_key IS NOT NULL AND audience_kind IS NOT NULL AND recipient IS NULL)
        OR (scope = 'RECIPIENT' AND recipient IS NOT NULL)
    ),
    CONSTRAINT chk_delivery_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT chk_delivery_max_attempts_positive CHECK (max_attempts >= 1)
);

-- Idempotencia por notificacion, destinatario y canal: expandir dos veces no manda dos correos.
CREATE UNIQUE INDEX ux_delivery_audience ON delivery (notification_id, channel, audience_key) WHERE scope = 'AUDIENCE';
CREATE UNIQUE INDEX ux_delivery_recipient ON delivery (notification_id, channel, recipient) WHERE scope = 'RECIPIENT';
-- Lo que el despachador reclama.
CREATE INDEX idx_delivery_due ON delivery (next_attempt_at) WHERE status IN ('PENDING', 'IN_PROGRESS');
CREATE INDEX idx_delivery_status_created ON delivery (status, created_at DESC);
CREATE INDEX idx_delivery_notification ON delivery (notification_id);
-- El tope de correos por direccion y hora.
CREATE INDEX idx_delivery_recipient_sent ON delivery (channel, recipient, sent_at) WHERE status = 'SENT';

-- ---------------------------------------------------------------------------------------------
-- Un disparo por regla, clave y ventana. El upsert condicional decide: fila afectada = dispara.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE rule_throttle (
    rule_key varchar(100) NOT NULL,
    dimension_key varchar(300) NOT NULL,
    fired_at timestamptz NOT NULL DEFAULT now(),
    until timestamptz NOT NULL,
    PRIMARY KEY (rule_key, dimension_key)
);

CREATE INDEX idx_rule_throttle_until ON rule_throttle (until);

-- ---------------------------------------------------------------------------------------------
-- La marca de agua del lector de Keycloak, una por fuente, con su arrendamiento: dos instancias
-- no leen la misma fuente a la vez (update ... where lease_until is null or lease_until < now(),
-- por recuento de filas). last_event_time solo avanza tras procesar la pasada entera.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE source_cursor (
    kind varchar(40) PRIMARY KEY,
    last_event_time timestamptz,
    lease_owner varchar(100),
    lease_until timestamptz,
    last_poll_at timestamptz,
    last_success_at timestamptz,
    last_error text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_source_cursor_kind CHECK (kind IN ('KEYCLOAK_LOGIN', 'KEYCLOAK_ADMIN'))
);

INSERT INTO source_cursor (kind) VALUES ('KEYCLOAK_LOGIN'), ('KEYCLOAK_ADMIN');
