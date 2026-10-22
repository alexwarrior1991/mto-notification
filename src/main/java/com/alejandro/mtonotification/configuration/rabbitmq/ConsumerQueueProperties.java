package com.alejandro.mtonotification.configuration.rabbitmq;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * El tipo de las colas que consume este servicio ({@code app.rabbitmq.queues.*}), el mismo para
 * todas: {@code classic}, por defecto, o {@code quorum}, replicada entre los nodos de un RabbitMQ
 * en cluster, que es como corren en OpenShift ({@code APP_RABBITMQ_QUEUES_TYPE=quorum}).
 *
 * <p>Con {@code classic} la cola se declara exactamente como antes, sin {@code x-queue-type}: una
 * cola ya creada sin el argumento y redeclarada con el es otra declaracion para el broker, que
 * responde {@code PRECONDITION_FAILED} y cierra el canal (se quedarian sin declarar las demas colas y
 * todos los bindings). Por lo mismo, cambiar el tipo de una cola que ya existe exige borrarla antes:
 * los argumentos de una cola son inmutables.</p>
 *
 * <p>La DLQ hereda el tipo de su cola: de poco sirve replicar la cola de trabajo si lo que falla acaba
 * en una sin replica. Solo la cola de trabajo lleva {@code x-delivery-limit}, porque la DLQ no la
 * consume nadie.</p>
 *
 * <p>Por que 5 entregas: los consumidores nunca reencolan (los reintentos ocurren dentro de la misma
 * entrega y lo que se agota va a la DLQ), asi que el contador del broker solo sube cuando un mensaje
 * vuelve sin ack: el consumidor murio, se corto la conexion o se apago con mensajes en vuelo. Cinco
 * deja pasar un par de reinicios normales y para el mensaje que tumba al consumidor cada vez, en vez
 * de dejarlo girar; y fijado aqui no depende del valor por defecto de cada version del broker.</p>
 */
@ConfigurationProperties(prefix = "app.rabbitmq.queues")
public record ConsumerQueueProperties(QueueType type, Integer deliveryLimit) {

    /** Entregas sin ack tras las que una cola quorum manda el mensaje a su DLQ. */
    public static final int DEFAULT_DELIVERY_LIMIT = 5;

    private static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    public ConsumerQueueProperties {
        type = type == null ? QueueType.CLASSIC : type;
        deliveryLimit = deliveryLimit == null ? DEFAULT_DELIVERY_LIMIT : deliveryLimit;
        if (deliveryLimit < 1) {
            throw new IllegalArgumentException(
                    "app.rabbitmq.queues.delivery-limit must be at least 1, was " + deliveryLimit);
        }
    }

    /** Una cola de trabajo, durable, con su descarte; quorum y con su limite de entregas si toca. */
    public Queue queue(String name, String deadLetterExchange, String deadLetterRoutingKey) {
        QueueBuilder builder = QueueBuilder.durable(name)
                .withArgument(ARG_DEAD_LETTER_EXCHANGE, deadLetterExchange)
                .withArgument(ARG_DEAD_LETTER_ROUTING_KEY, deadLetterRoutingKey);
        if (type == QueueType.QUORUM) {
            builder.quorum().deliveryLimit(deliveryLimit);
        }
        return builder.build();
    }

    /** La DLQ de una cola: durable, del mismo tipo y sin limite de entregas. */
    public Queue deadLetterQueue(String name) {
        QueueBuilder builder = QueueBuilder.durable(name);
        if (type == QueueType.QUORUM) {
            builder.quorum();
        }
        return builder.build();
    }

    public enum QueueType {
        CLASSIC,
        QUORUM
    }
}
