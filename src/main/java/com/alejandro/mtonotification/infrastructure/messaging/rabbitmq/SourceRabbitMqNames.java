package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

/**
 * Nombres AMQP de las fuentes. Los exchanges y los patrones son contrato de cada emisor; las
 * colas, con su DLX y su DLQ, son de este servicio. Son los valores por defecto de
 * {@code app.rabbitmq.sources.*} y los que llevan las anotaciones de los consumidores.
 */
public final class SourceRabbitMqNames {

    public static final String MASTER_DATA_SOURCE = "master-data";
    public static final String MASTER_DATA_EXCHANGE = "mto.master-data.exchange";
    public static final String MASTER_DATA_ROUTING_PATTERN = "mto.master-data.#";
    public static final String MASTER_DATA_QUEUE = "mto.notification.master-data.queue";
    public static final String MASTER_DATA_DEAD_LETTER_EXCHANGE = MASTER_DATA_QUEUE + ".dlx";
    public static final String MASTER_DATA_DEAD_LETTER_QUEUE = MASTER_DATA_QUEUE + ".dlq";
    public static final String MASTER_DATA_DEAD_LETTER_ROUTING_KEY = MASTER_DATA_QUEUE + ".dlq";

    /** Lo que mto-configuration cuenta de si mismo (hoy, el final de un trabajo): su propio exchange. */
    public static final String CONFIGURATION_SOURCE = "configuration";
    public static final String CONFIGURATION_EXCHANGE = "mto.configuration.exchange";
    public static final String CONFIGURATION_ROUTING_PATTERN = "mto.configuration.#";
    public static final String CONFIGURATION_QUEUE = "mto.notification.configuration.queue";
    public static final String CONFIGURATION_DEAD_LETTER_EXCHANGE = CONFIGURATION_QUEUE + ".dlx";
    public static final String CONFIGURATION_DEAD_LETTER_QUEUE = CONFIGURATION_QUEUE + ".dlq";
    public static final String CONFIGURATION_DEAD_LETTER_ROUTING_KEY = CONFIGURATION_QUEUE + ".dlq";

    /** Las acciones administrativas de mto-users, con la persona que las hizo. */
    public static final String USERS_SOURCE = "users";
    public static final String USERS_EXCHANGE = "mto.users.exchange";
    public static final String USERS_ROUTING_PATTERN = "mto.users.#";
    public static final String USERS_QUEUE = "mto.notification.users.queue";
    public static final String USERS_DEAD_LETTER_EXCHANGE = USERS_QUEUE + ".dlx";
    public static final String USERS_DEAD_LETTER_QUEUE = USERS_QUEUE + ".dlq";
    public static final String USERS_DEAD_LETTER_ROUTING_KEY = USERS_QUEUE + ".dlq";

    /** Lo que mto-maintenance cuenta de si mismo: ordenes, defectos, inspecciones, turnos, material, activos y preventivos. */
    public static final String MAINTENANCE_SOURCE = "maintenance";
    public static final String MAINTENANCE_EXCHANGE = "mto.maintenance.exchange";
    public static final String MAINTENANCE_ROUTING_PATTERN = "mto.maintenance.#";
    public static final String MAINTENANCE_QUEUE = "mto.notification.maintenance.queue";
    public static final String MAINTENANCE_DEAD_LETTER_EXCHANGE = MAINTENANCE_QUEUE + ".dlx";
    public static final String MAINTENANCE_DEAD_LETTER_QUEUE = MAINTENANCE_QUEUE + ".dlq";
    public static final String MAINTENANCE_DEAD_LETTER_ROUTING_KEY = MAINTENANCE_QUEUE + ".dlq";

    /** Lo que mto-stock cuenta de si mismo: un material bajo minimo, reservas canceladas o liberadas y ajustes. */
    public static final String STOCK_SOURCE = "stock";
    public static final String STOCK_EXCHANGE = "mto.stock.exchange";
    public static final String STOCK_ROUTING_PATTERN = "mto.stock.#";
    public static final String STOCK_QUEUE = "mto.notification.stock.queue";
    public static final String STOCK_DEAD_LETTER_EXCHANGE = STOCK_QUEUE + ".dlx";
    public static final String STOCK_DEAD_LETTER_QUEUE = STOCK_QUEUE + ".dlq";
    public static final String STOCK_DEAD_LETTER_ROUTING_KEY = STOCK_QUEUE + ".dlq";

    /** Lo que mto-field cuenta de una posesion de via: abierta y cerrada, el desalojo con sus acuses y la salida de via de cada equipo. */
    public static final String FIELD_SOURCE = "field";
    public static final String FIELD_EXCHANGE = "mto.field.exchange";
    public static final String FIELD_ROUTING_PATTERN = "mto.field.#";
    public static final String FIELD_QUEUE = "mto.notification.field.queue";
    public static final String FIELD_DEAD_LETTER_EXCHANGE = FIELD_QUEUE + ".dlx";
    public static final String FIELD_DEAD_LETTER_QUEUE = FIELD_QUEUE + ".dlq";
    public static final String FIELD_DEAD_LETTER_ROUTING_KEY = FIELD_QUEUE + ".dlq";

    public static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    public static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private SourceRabbitMqNames() {
    }
}
