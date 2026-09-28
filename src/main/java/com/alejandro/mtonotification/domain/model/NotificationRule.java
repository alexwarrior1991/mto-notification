package com.alejandro.mtonotification.domain.model;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Una regla del YAML, ya validada: que eventos la disparan, con que condicion, a quien avisa, por
 * donde, con que texto y con que freno.
 *
 * <p>Las plantillas ({@code title}, {@code body}, {@code link}, las audiencias y la clave del
 * freno) son expresiones de plantilla de SpEL ({@code #{...}}) que evalua el motor con
 * {@code event}, {@code payload} y {@code vars}; aqui solo se guarda el texto.</p>
 *
 * @param key        identificador estable de la regla; es el {@code rule_key} de la notificacion
 * @param matcher    que tipos de evento la disparan
 * @param when       condicion SpEL, o {@code null} si dispara siempre
 * @param severity   gravedad del aviso, o {@code null} para heredar la del evento
 * @param audiences  plantillas {@code KIND:clave}; una que evalue en blanco se omite
 * @param channels   {@code inbox} y/o {@code email}
 * @param title      plantilla del titulo
 * @param body       plantilla del cuerpo, o {@code null}
 * @param link       plantilla de la ruta del backoffice, o {@code null}
 * @param throttle   el freno, o {@code null}
 */
public record NotificationRule(
        String key,
        EventTypeMatcher matcher,
        String when,
        ActivitySeverity severity,
        List<String> audiences,
        List<String> channels,
        String title,
        String body,
        String link,
        Throttle throttle
) {

    public static final Pattern KEY_SHAPE = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    public static final int MAX_KEY_LENGTH = 100;

    public NotificationRule {
        key = DomainValidations.requireNonBlank(key, "rule key").trim();
        if (!KEY_SHAPE.matcher(key).matches() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Rule key '" + key + "' must be kebab-case and at most " + MAX_KEY_LENGTH + " characters");
        }
        DomainValidations.requireNonNull(matcher, "rule '" + key + "': event");
        when = DomainValidations.trimToNull(when);
        audiences = List.copyOf(audiences == null ? List.of() : audiences);
        if (audiences.isEmpty()) {
            throw new IllegalArgumentException("Rule '" + key + "' has no audiences");
        }
        for (String audience : audiences) {
            validateAudienceTemplate(key, audience);
        }
        channels = List.copyOf(channels == null || channels.isEmpty() ? List.of(DeliveryChannels.INBOX)
                : channels.stream().map(channel -> channel.trim().toLowerCase()).distinct().toList());
        for (String channel : channels) {
            if (!DeliveryChannels.isKnown(channel)) {
                throw new IllegalArgumentException("Rule '" + key + "' names an unknown channel '" + channel + "'");
            }
        }
        title = DomainValidations.requireNonBlank(title, "rule '" + key + "': title").trim();
        body = DomainValidations.trimToNull(body);
        link = DomainValidations.trimToNull(link);
    }

    /**
     * La clase de la audiencia tiene que ser literal: es lo unico que se valida al arrancar, y una
     * plantilla que dejara decidir la clase en tiempo de ejecucion podria acabar dirigiendo un
     * aviso a un {@code kind} que nadie resuelve.
     */
    private static void validateAudienceTemplate(String key, String template) {
        String value = DomainValidations.requireNonBlank(template, "rule '" + key + "': audience").trim();
        int separator = value.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Rule '" + key + "': audience '" + value + "' is not <KIND>:<key>");
        }
        if (AudienceKind.parse(value.substring(0, separator)).isEmpty()) {
            throw new IllegalArgumentException("Rule '" + key + "': audience kind '" + value.substring(0, separator) + "' is unknown");
        }
    }

    /**
     * Un disparo por regla, clave y ventana: una orden urgente no avisa dos veces en diez minutos.
     *
     * @param window cuanto dura el freno tras un disparo
     * @param key    plantilla de la dimension ({@code #{event.subjectId}}); en blanco, la regla entera
     */
    public record Throttle(Duration window, String key) {

        public Throttle {
            DomainValidations.requireNonNull(window, "throttle window");
            if (window.isNegative() || window.isZero()) {
                throw new IllegalArgumentException("A throttle window must be positive");
            }
            key = DomainValidations.trimToNull(key);
        }
    }
}
