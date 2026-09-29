package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.ActivityIngestor;
import com.alejandro.mtonotification.application.service.DerivedEventDetector;
import com.alejandro.mtonotification.application.service.RuleEngine;
import com.alejandro.mtonotification.domain.model.ActivityEventDraft;
import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.repository.ActivityEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Escribe la linea con la insercion condicional y, si era nueva, la pasa por las reglas y los
 * detectores. Un 0 en la insercion es una repeticion de la fuente: no se hace nada mas, que es lo
 * que evita avisar dos veces por el mismo evento.
 */
@Service
class ActivityIngestorImpl implements ActivityIngestor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActivityIngestorImpl.class);

    private final ActivityEventRepository activityEventRepository;
    private final RuleEngine ruleEngine;
    private final List<DerivedEventDetector> detectors;
    private final JsonPayloads jsonPayloads;

    ActivityIngestorImpl(ActivityEventRepository activityEventRepository, RuleEngine ruleEngine,
                         List<DerivedEventDetector> detectors, JsonPayloads jsonPayloads) {
        this.activityEventRepository = activityEventRepository;
        this.ruleEngine = ruleEngine;
        this.detectors = List.copyOf(detectors);
        this.jsonPayloads = jsonPayloads;
    }

    @Override
    @Transactional
    public Optional<ActivityEvent> ingest(ActivityEventDraft draft) {
        int inserted = activityEventRepository.insertIfMissing(
                draft.sourceService(), draft.sourceEventId(), draft.category().name(), draft.type(),
                draft.severity().name(), draft.occurredAt(), draft.actor().kind().name(), draft.actor().username(),
                draft.actor().id(), draft.subject().type(), draft.subject().id(), draft.subject().label(),
                draft.correlationId(), draft.ipAddress(), draft.eventCount(), jsonPayloads.write(draft.payload()));

        if (inserted == 0) {
            LOGGER.debug("Activity event already recorded, nothing to do: source={}, sourceEventId={}",
                    draft.sourceService(), draft.sourceEventId());
            return Optional.empty();
        }

        ActivityEvent event = activityEventRepository
                .findBySourceServiceAndSourceEventId(draft.sourceService(), draft.sourceEventId())
                .orElseThrow(() -> new IllegalStateException("Activity event vanished right after being inserted: "
                        + draft.sourceService() + "/" + draft.sourceEventId()));

        LOGGER.info("Activity recorded: type={}, severity={}, actor={}, subject={}/{}, source={}",
                event.getType(), event.getSeverity(), event.getActorUsername(), event.getSubjectType(),
                event.getSubjectId(), event.getSourceService());

        ruleEngine.evaluate(event, draft.payload());
        for (DerivedEventDetector detector : detectors) {
            detector.afterIngested(event, draft);
        }
        return Optional.of(event);
    }
}
