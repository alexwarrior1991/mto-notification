package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de mto-field: lo que la consola de una posesion de via cuenta desde su
 * outbox (la posesion abierta y cerrada, el desalojo emitido, cada acuse, el equipo que no acusa a
 * tiempo y la salida de via de cada equipo), un evento por mensaje con quien lo hizo. Un
 * {@code @Bean} de {@code RabbitMqConfiguration}, con la cola por placeholder y el mismo valor por
 * defecto que la declaracion.
 */
public class FieldSourceConsumer {

    private final SourceEventConsumer consumer;

    public FieldSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.FIELD_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.field.queue:" + SourceRabbitMqNames.FIELD_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onFieldEvent(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
