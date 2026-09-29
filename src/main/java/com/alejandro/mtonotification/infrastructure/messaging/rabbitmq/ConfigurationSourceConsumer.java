package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de los eventos propios de mto-configuration ({@code job.finished}).
 * Como el de datos maestros: un {@code @Bean} de {@code RabbitMqConfiguration}, con la cola por
 * placeholder y el mismo valor por defecto que la declaracion.
 */
public class ConfigurationSourceConsumer {

    private final SourceEventConsumer consumer;

    public ConfigurationSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.CONFIGURATION_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.configuration.queue:" + SourceRabbitMqNames.CONFIGURATION_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onConfigurationEvent(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
