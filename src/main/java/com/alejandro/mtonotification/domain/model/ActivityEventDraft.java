package com.alejandro.mtonotification.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Una linea del registro antes de escribirse: lo que un adaptador construye a partir de lo que
 * dijo su fuente. La categoria sale del tipo; la idempotencia, de {@code sourceService} y
 * {@code sourceEventId}.
 *
 * @param sourceService  quien lo dijo ({@code keycloak-login}, {@code mto-configuration}, o este
 *                       servicio para los derivados)
 * @param sourceEventId  el identificador del evento en la fuente, estable entre repeticiones
 * @param type           {@code <categoria>.<sujeto>.<evento>}
 * @param severity       la gravedad de la linea (la regla puede subirla en el aviso)
 * @param occurredAt     cuando paso, segun la fuente
 * @param actor          quien
 * @param subject        sobre que
 * @param correlationId  la peticion o el trabajo que lo causo, si viaja
 * @param ipAddress      solo en ACCESS; en cualquier otra categoria se descarta al escribir
 * @param eventCount     cuantos eventos de la fuente resume (1 salvo en una rafaga)
 * @param payload        lo que se guarda de lo que dijo la fuente, ya por lista blanca
 */
public record ActivityEventDraft(
        String sourceService,
        String sourceEventId,
        String type,
        ActivitySeverity severity,
        Instant occurredAt,
        Actor actor,
        Subject subject,
        String correlationId,
        String ipAddress,
        int eventCount,
        Map<String, Object> payload
) {

    public static final int MAX_SOURCE_SERVICE_LENGTH = 100;
    public static final int MAX_SOURCE_EVENT_ID_LENGTH = 200;
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    public ActivityEventDraft {
        sourceService = DomainValidations.requireNonBlank(sourceService, "sourceService").trim();
        sourceEventId = DomainValidations.requireNonBlank(sourceEventId, "sourceEventId").trim();
        if (sourceService.length() > MAX_SOURCE_SERVICE_LENGTH || sourceEventId.length() > MAX_SOURCE_EVENT_ID_LENGTH) {
            // No se recorta: dos identificadores que se recortasen al mismo valor harian que un
            // evento legitimo se descartase como repetido.
            throw new IllegalArgumentException("sourceService or sourceEventId is too long for the registry");
        }
        type = ActivityTypes.requireWellFormed(type);
        severity = severity == null ? ActivitySeverity.INFO : severity;
        DomainValidations.requireNonNull(occurredAt, "occurredAt");
        actor = actor == null ? Actor.system() : actor;
        subject = subject == null ? Subject.none() : subject;
        correlationId = DomainValidations.truncate(DomainValidations.trimToNull(correlationId), MAX_CORRELATION_ID_LENGTH);
        // Sobre el parametro, no sobre category(): dentro del constructor compacto el campo aun no existe.
        ipAddress = ActivityCategory.ofType(type) == ActivityCategory.ACCESS ? DomainValidations.trimToNull(ipAddress) : null;
        if (eventCount < 1) {
            throw new IllegalArgumentException("eventCount must be at least 1");
        }
        payload = PayloadSanitizer.sanitize(payload);
    }

    public ActivityCategory category() {
        return ActivityCategory.ofType(type);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Constructor fluido para los adaptadores; lo que no se pone toma el valor neutro. */
    public static final class Builder {
        private String sourceService;
        private String sourceEventId;
        private String type;
        private ActivitySeverity severity = ActivitySeverity.INFO;
        private Instant occurredAt;
        private Actor actor = Actor.system();
        private Subject subject = Subject.none();
        private String correlationId;
        private String ipAddress;
        private int eventCount = 1;
        private Map<String, Object> payload = Map.of();

        private Builder() {
        }

        public Builder source(String sourceService, String sourceEventId) {
            this.sourceService = sourceService;
            this.sourceEventId = sourceEventId;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder severity(ActivitySeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder actor(Actor actor) {
            this.actor = actor;
            return this;
        }

        public Builder subject(Subject subject) {
            this.subject = subject;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder eventCount(int eventCount) {
            this.eventCount = eventCount;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload;
            return this;
        }

        public ActivityEventDraft build() {
            return new ActivityEventDraft(sourceService, sourceEventId, type, severity, occurredAt, actor, subject,
                    correlationId, ipAddress, eventCount, payload);
        }
    }
}
