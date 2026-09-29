package com.alejandro.mtonotification.configuration.notification;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lo propio de este servicio ({@code app.notification.*}): las reglas, la racha, las rafagas, la
 * bandeja, las entregas, el correo y la retencion. Los valores por defecto viven en
 * {@code application.yaml}; los records solo dan los de lo que alli no se pone.
 */
@Validated
@ConfigurationProperties(prefix = "app.notification")
public record NotificationProperties(
        @NotBlank String rulesLocation,
        Rules rules,
        Access access,
        Burst burst,
        Inbox inbox,
        Delivery delivery,
        Email email,
        Retention retention
) {

    public NotificationProperties {
        rules = rules == null ? new Rules(Map.of()) : rules;
        access = access == null ? new Access(null) : access;
        burst = burst == null ? new Burst(null, null, 0, null, true, null, null) : burst;
        inbox = inbox == null ? new Inbox(0, 0) : inbox;
        delivery = delivery == null ? new Delivery(true, null, null, 0, 0, null, null, null, true) : delivery;
        email = email == null ? new Email(true, null, null, null, 0) : email;
        retention = retention == null ? new Retention(true, null, 0, 0, null, null, null, null, null) : retention;
    }

    /** {@code vars['...']} en las expresiones de las reglas. */
    public record Rules(Map<String, Object> variables) {
        public Rules {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    public record Access(Streak streak) {
        public Access {
            streak = streak == null ? new Streak(3, Duration.ofMinutes(10)) : streak;
        }
    }

    /** @param threshold fallos que hacen racha; @param window en cuanto tiempo */
    public record Streak(@Min(2) int threshold, @NotNull Duration window) {
    }

    /**
     * @param idleTimeout      sin eventos nuevos durante esto, la rafaga se cierra
     * @param maxWindow        abierta mas de esto, se cierra aunque sigan llegando
     * @param sampleSize       ids que se guardan de muestra
     * @param closeFixedDelay  cada cuanto mira el cerrador
     * @param closeEnabled     el cerrador; los tests lo apagan y lo llaman a mano
     * @param directDeletions  entidades cuya baja no se agrega
     * @param directCreations  entidades cuya alta no se agrega
     */
    public record Burst(Duration idleTimeout, Duration maxWindow, @Min(0) int sampleSize, Duration closeFixedDelay,
                        boolean closeEnabled, Set<String> directDeletions, Set<String> directCreations) {
        public Burst {
            idleTimeout = idleTimeout == null ? Duration.ofSeconds(30) : idleTimeout;
            maxWindow = maxWindow == null ? Duration.ofMinutes(10) : maxWindow;
            sampleSize = sampleSize <= 0 ? 20 : sampleSize;
            closeFixedDelay = closeFixedDelay == null ? Duration.ofSeconds(10) : closeFixedDelay;
            // Sin valor (ni en el YAML ni en un test que construye el record a mano): lo que documenta
            // docs/02: se agregan las altas y modificaciones de las ocho entidades, y van directas las
            // bajas de la infraestructura y el alta de un paquete. Una lista vacia explicita es «nada».
            directDeletions = directDeletions == null ? DEFAULT_DIRECT_DELETIONS : normalize(directDeletions);
            directCreations = directCreations == null ? DEFAULT_DIRECT_CREATIONS : normalize(directCreations);
        }

        public static final Set<String> DEFAULT_DIRECT_DELETIONS = Set.of("track", "station", "profile", "disconnector", "section-insulator");
        public static final Set<String> DEFAULT_DIRECT_CREATIONS = Set.of("execution-package");

        private static Set<String> normalize(Set<String> names) {
            Set<String> normalized = new LinkedHashSet<>();
            for (String name : names == null ? Set.<String>of() : names) {
                if (name != null && !name.isBlank()) {
                    normalized.add(name.trim().toLowerCase());
                }
            }
            return Set.copyOf(normalized);
        }
    }

    /** @param unreadCountCap tope del contador («99+»); @param maxPageSize tamano maximo de pagina */
    public record Inbox(int unreadCountCap, int maxPageSize) {
        public Inbox {
            unreadCountCap = unreadCountCap <= 0 ? 100 : unreadCountCap;
            maxPageSize = maxPageSize <= 0 ? 100 : maxPageSize;
        }
    }

    public record Delivery(boolean enabled, Duration fixedDelay, Duration initialDelay, int batchSize, int maxAttempts,
                           Duration initialRetryDelay, Duration maxRetryDelay, Duration claimVisibilityTimeout,
                           boolean immediateDispatch) {
        public Delivery {
            fixedDelay = fixedDelay == null ? Duration.ofSeconds(15) : fixedDelay;
            initialDelay = initialDelay == null ? Duration.ofSeconds(20) : initialDelay;
            batchSize = batchSize <= 0 ? 50 : batchSize;
            maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
            initialRetryDelay = initialRetryDelay == null ? Duration.ofSeconds(30) : initialRetryDelay;
            maxRetryDelay = maxRetryDelay == null ? Duration.ofHours(1) : maxRetryDelay;
            claimVisibilityTimeout = claimVisibilityTimeout == null ? Duration.ofMinutes(5) : claimVisibilityTimeout;
        }
    }

    public record Email(boolean enabled, String from, String subjectPrefix, String linkBaseUrl, int maxPerHour) {
        public Email {
            from = from == null || from.isBlank() ? "notificaciones@mto.local" : from.trim();
            subjectPrefix = subjectPrefix == null ? "[MTO]" : subjectPrefix.trim();
            linkBaseUrl = linkBaseUrl == null || linkBaseUrl.isBlank() ? "http://localhost:8085" : stripTrailingSlash(linkBaseUrl.trim());
            maxPerHour = maxPerHour <= 0 ? 20 : maxPerHour;
        }

        private static String stripTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    public record Retention(boolean purgeEnabled, String cron, int batchSize, int maxBatchesPerRun,
                            ActivityRetention activity, Duration notifications, Duration inbox, Duration bursts,
                            Duration throttles) {
        public Retention {
            cron = cron == null || cron.isBlank() ? "0 17 3 * * *" : cron;
            batchSize = batchSize <= 0 ? 1000 : batchSize;
            maxBatchesPerRun = maxBatchesPerRun <= 0 ? 50 : maxBatchesPerRun;
            activity = activity == null ? new ActivityRetention(null, null, null, null, null, null) : activity;
            notifications = notifications == null ? Duration.ofDays(180) : notifications;
            inbox = inbox == null ? Duration.ofDays(7) : inbox;
            bursts = bursts == null ? Duration.ofDays(1) : bursts;
            throttles = throttles == null ? Duration.ofDays(1) : throttles;
        }
    }

    public record ActivityRetention(Duration access, Duration users, Duration configuration, Duration maintenance,
                                    Duration stock, Duration system) {
        public ActivityRetention {
            access = access == null ? Duration.ofDays(90) : access;
            users = users == null ? Duration.ofDays(400) : users;
            configuration = configuration == null ? Duration.ofDays(400) : configuration;
            maintenance = maintenance == null ? Duration.ofDays(400) : maintenance;
            stock = stock == null ? Duration.ofDays(400) : stock;
            system = system == null ? Duration.ofDays(90) : system;
        }
    }
}
