package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de datos maestros. Es un {@code @Bean} de {@code RabbitMqConfiguration}
 * para heredar su condicion; la cola se resuelve por placeholder con el mismo valor por defecto
 * que la declaracion, asi que las dos no pueden acabar apuntando a sitios distintos.
 */
public class MasterDataSourceConsumer {

    private final SourceEventConsumer consumer;

    public MasterDataSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.MASTER_DATA_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.master-data.queue:" + SourceRabbitMqNames.MASTER_DATA_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onMasterDataChanged(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
