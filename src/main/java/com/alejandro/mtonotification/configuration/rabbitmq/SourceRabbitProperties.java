package com.alejandro.mtonotification.configuration.rabbitmq;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Las fuentes de RabbitMQ ({@code app.rabbitmq.sources.<fuente>.*}): por cada una, el exchange y la
 * routing key (contrato del emisor) y la cola con su DLX y su DLQ (de este servicio). Una cadena
 * en blanco es un despliegue a medio configurar y se rechaza en el arranque, no al primer mensaje.
 */
@Validated
@ConfigurationProperties(prefix = "app.rabbitmq")
public record SourceRabbitProperties(boolean enabled, Map<String, Source> sources) {

    public SourceRabbitProperties {
        Map<String, Source> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Source> entry : (sources == null ? Map.<String, Source>of() : sources).entrySet()) {
            normalized.put(entry.getKey().trim().toLowerCase(), entry.getValue());
        }
        sources = Map.copyOf(normalized);
    }

    public Source source(String id) {
        Source source = sources.get(id);
        if (source == null) {
            throw new IllegalStateException("No RabbitMQ source configured with id '" + id + "'");
        }
        return source;
    }

    public record Source(
            @NotBlank String exchange,
            @NotBlank String routingKey,
            @NotBlank String queue,
            @NotBlank String deadLetterExchange,
            @NotBlank String deadLetterQueue,
            @NotBlank String deadLetterRoutingKey,
            boolean listenerEnabled
    ) {
        public Source {
            for (String value : new String[]{exchange, routingKey, queue, deadLetterExchange, deadLetterQueue, deadLetterRoutingKey}) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("A RabbitMQ source has a blank name; every exchange, queue and routing key is required");
                }
            }
        }
    }
}
