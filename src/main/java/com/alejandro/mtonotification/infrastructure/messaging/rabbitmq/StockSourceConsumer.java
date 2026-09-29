package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de mto-stock: lo que el almacen cuenta de si mismo desde su outbox (un
 * material por debajo de su minimo, una reserva cancelada o liberada, un ajuste de inventario), un
 * evento por mensaje con quien lo hizo. Un {@code @Bean} de {@code RabbitMqConfiguration}, con la
 * cola por placeholder y el mismo valor por defecto que la declaracion.
 */
public class StockSourceConsumer {

    private final SourceEventConsumer consumer;

    public StockSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.STOCK_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.stock.queue:" + SourceRabbitMqNames.STOCK_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onStockEvent(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
