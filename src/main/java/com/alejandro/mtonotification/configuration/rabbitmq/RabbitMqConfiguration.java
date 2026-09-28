package com.alejandro.mtonotification.configuration.rabbitmq;

import com.alejandro.mtonotification.application.service.SourceEventProcessor;
import com.alejandro.mtonotification.configuration.messaging.MessagePayloadSignatureVerifier;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.MasterDataSourceConsumer;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.RabbitListenerContainerFactoryNames;
import com.alejandro.mtonotification.infrastructure.messaging.rabbitmq.SourceRabbitMqNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Declara la topologia de cada fuente y cablea sus consumidores. Este servicio solo consume: no
 * hay {@code RabbitTemplate} ni confirms. Cada exchange se redeclara con los atributos del emisor
 * (topic, durable, sin auto-delete): una diferencia hace que el broker cierre el canal y la cola se
 * quede sin binding, sin ningun error despues del arranque. Todo se apaga con
 * {@code app.rabbitmq.enabled=false}: sin estos beans nadie abre una conexion.
 */
@Configuration
@EnableConfigurationProperties(SourceRabbitProperties.class)
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqConfiguration.class);

    private final SourceRabbitProperties properties;

    public RabbitMqConfiguration(SourceRabbitProperties properties) {
        this.properties = properties;
    }

    /** Exchange, cola, binding, DLX, DLQ y su binding, por fuente; el {@code RabbitAdmin} de Boot los declara. */
    @Bean
    public Declarables sourceTopology() {
        List<Declarable> declarables = new ArrayList<>();
        for (Map.Entry<String, SourceRabbitProperties.Source> entry : properties.sources().entrySet()) {
            SourceRabbitProperties.Source source = entry.getValue();
            TopicExchange exchange = new TopicExchange(source.exchange(), true, false);
            Queue queue = QueueBuilder.durable(source.queue())
                    .withArgument(SourceRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE, source.deadLetterExchange())
                    .withArgument(SourceRabbitMqNames.ARG_DEAD_LETTER_ROUTING_KEY, source.deadLetterRoutingKey())
                    .build();
            DirectExchange deadLetterExchange = new DirectExchange(source.deadLetterExchange(), true, false);
            Queue deadLetterQueue = QueueBuilder.durable(source.deadLetterQueue()).build();
            declarables.add(exchange);
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(exchange).with(source.routingKey()));
            declarables.add(deadLetterExchange);
            declarables.add(deadLetterQueue);
            declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(source.deadLetterRoutingKey()));
            LOGGER.info("Source '{}': queue={} bound to {} with {}, dead letters to {} via {}", entry.getKey(), source.queue(),
                    source.exchange(), source.routingKey(), source.deadLetterQueue(), source.deadLetterExchange());
        }
        return new Declarables(declarables);
    }

    /**
     * Parte del {@link JsonMapper} de la aplicacion sin fallar por propiedades desconocidas: un
     * emisor puede anadir claves sin coordinarse con cada consumidor. El tipo destino lo da el
     * metodo anotado, porque los emisores publican bytes sin {@code __TypeId__}.
     */
    @Bean
    public MessageConverter sourceMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    }

    /**
     * Pasa por el configurer de Boot (sin el se descarta {@code spring.rabbitmq.listener.simple.*})
     * y no reencola nunca lo rechazado: con el valor por defecto un mensaje malo giraria sin fin.
     */
    @Bean(RabbitListenerContainerFactoryNames.SOURCES)
    public SimpleRabbitListenerContainerFactory sourceRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessageConverter sourceMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(sourceMessageConverter);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.rabbitmq.sources.master-data", name = "listener-enabled", havingValue = "true", matchIfMissing = true)
    public MasterDataSourceConsumer masterDataSourceConsumer(SourceEventProcessor processor, MessagePayloadSignatureVerifier signatureVerifier) {
        LOGGER.info("Master data consumer enabled on {}", properties.source(SourceRabbitMqNames.MASTER_DATA_SOURCE).queue());
        return new MasterDataSourceConsumer(processor, signatureVerifier);
    }
}
