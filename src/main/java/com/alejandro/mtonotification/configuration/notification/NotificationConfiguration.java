package com.alejandro.mtonotification.configuration.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Publica {@link NotificationProperties}. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {
}
