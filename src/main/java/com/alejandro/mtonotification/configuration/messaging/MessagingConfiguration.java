package com.alejandro.mtonotification.configuration.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the message signature check.
 *
 * <p>No está bajo la condición de {@code app.rabbitmq.enabled}: la firma es del mensaje, no del
 * transporte, y con el consumidor apagado el bean simplemente no lo usa nadie.</p>
 */
@Configuration
@EnableConfigurationProperties(MessageSignatureProperties.class)
public class MessagingConfiguration {

    @Bean
    public MessagePayloadSignatureVerifier messagePayloadSignatureVerifier(MessageSignatureProperties properties) {
        return new MessagePayloadSignatureVerifier(properties);
    }
}
