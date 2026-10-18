package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de mto-resources: lo que el servicio de personal, organizacion y flota
 * cuenta desde su outbox (las altas, los cambios y las bajas de personas, equipos y vehiculos), un
 * evento por mensaje con quien lo hizo. Un {@code @Bean} de {@code RabbitMqConfiguration}, con la
 * cola por placeholder y el mismo valor por defecto que la declaracion.
 */
public class ResourcesSourceConsumer {

    private final SourceEventConsumer consumer;

    public ResourcesSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.RESOURCES_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.resources.queue:" + SourceRabbitMqNames.RESOURCES_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onResourcesEvent(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
