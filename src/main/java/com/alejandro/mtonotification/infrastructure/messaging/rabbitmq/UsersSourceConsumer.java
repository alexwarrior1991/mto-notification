package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de mto-users: una accion administrativa por mensaje, con la persona que
 * la hizo. Un {@code @Bean} de {@code RabbitMqConfiguration}, con la cola por placeholder y el
 * mismo valor por defecto que la declaracion.
 */
public class UsersSourceConsumer {

    private final SourceEventConsumer consumer;

    public UsersSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.USERS_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.users.queue:" + SourceRabbitMqNames.USERS_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onUsersEvent(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
