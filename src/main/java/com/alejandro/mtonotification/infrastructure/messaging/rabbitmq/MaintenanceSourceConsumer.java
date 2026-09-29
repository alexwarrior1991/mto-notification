package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * El consumidor de la cola de mto-maintenance: lo que ese servicio cuenta de si mismo desde su
 * outbox (ordenes, defectos, inspecciones, turnos, material, activos y preventivos), un evento por
 * mensaje con quien lo hizo. Un {@code @Bean} de {@code RabbitMqConfiguration}, con la cola por
 * placeholder y el mismo valor por defecto que la declaracion.
 */
public class MaintenanceSourceConsumer {

    private final SourceEventConsumer consumer;

    public MaintenanceSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        this.consumer = new SourceEventConsumer(SourceRabbitMqNames.MAINTENANCE_SOURCE, processor, signatureVerifier);
    }

    @RabbitListener(
            queues = "${app.rabbitmq.sources.maintenance.queue:" + SourceRabbitMqNames.MAINTENANCE_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.SOURCES)
    public void onMaintenanceEvent(SourceEnvelope envelope, Message rawMessage) {
        consumer.consume(envelope, rawMessage);
    }
}
