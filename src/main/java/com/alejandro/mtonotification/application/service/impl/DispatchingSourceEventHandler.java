package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;
import com.alejandro.mtonotification.application.exception.UnprocessableSourceEventException;
import com.alejandro.mtonotification.application.service.ActivitySourceAdapter;
import com.alejandro.mtonotification.application.service.SourceEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reparte cada sobre al adaptador de su fuente. Sin adaptador para una fuente no se puede decidir
 * nada, y como una fuente sin adaptador tampoco tiene cola, es un error de programacion que se
 * lanza como permanente. Dos adaptadores para la misma fuente impiden arrancar.
 */
@Service
class DispatchingSourceEventHandler implements SourceEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchingSourceEventHandler.class);

    private final Map<String, ActivitySourceAdapter> adaptersBySource;

    DispatchingSourceEventHandler(List<ActivitySourceAdapter> adapters) {
        Map<String, ActivitySourceAdapter> index = new LinkedHashMap<>();
        for (ActivitySourceAdapter adapter : adapters) {
            String sourceId = adapter.sourceId() == null ? "" : adapter.sourceId().trim().toLowerCase();
            if (sourceId.isEmpty()) {
                throw new IllegalStateException(adapter.getClass().getName() + " returns a blank sourceId");
            }
            ActivitySourceAdapter previous = index.put(sourceId, adapter);
            if (previous != null) {
                throw new IllegalStateException("Two adapters claim the source '%s': %s and %s"
                        .formatted(sourceId, previous.getClass().getName(), adapter.getClass().getName()));
            }
        }
        this.adaptersBySource = Map.copyOf(index);
        LOGGER.info("Source dispatcher ready: {} adapter(s) registered for {}", index.size(), index.keySet());
    }

    @Override
    public void handle(SourceEnvelope envelope, SourceEventContext context) {
        ActivitySourceAdapter adapter = adaptersBySource.get(context.sourceId() == null ? "" : context.sourceId().toLowerCase());
        if (adapter == null) {
            throw new UnprocessableSourceEventException("No adapter registered for source '" + context.sourceId() + "'");
        }
        LOGGER.info("Source event received: source={}, eventType={}, operationId={}, origin={}, sequenceNumber={}",
                context.sourceId(), envelope.eventType(), envelope.operationId(), envelope.origin(), context.sequenceNumber());
        adapter.handle(envelope, context);
    }
}
