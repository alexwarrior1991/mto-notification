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

    public static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    public static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private SourceRabbitMqNames() {
    }
}
