package com.alejandro.mtonotification.configuration.mail;

import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.infrastructure.mail.EmailChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Registra el canal de correo con {@code app.notification.email.enabled=true}. Apagado, las
 * entregas por correo quedan SKIPPED con su motivo y todo lo demas sigue igual: lo que se apaga es
 * el canal, no la regla.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.notification.email", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MailChannelConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailChannelConfiguration.class);

    @Bean
    public EmailChannel emailChannel(JavaMailSender mailSender, NotificationProperties properties) {
        LOGGER.info("Email channel enabled: from={}, links under {}", properties.email().from(), properties.email().linkBaseUrl());
        return new EmailChannel(mailSender, properties.email());
    }
}
