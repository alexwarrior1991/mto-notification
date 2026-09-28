package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.messaging.SourceEnvelope;
import com.alejandro.mtonotification.application.dto.messaging.SourceEventContext;

/**
 * La frontera entre el transporte y la aplicacion: lo que el inbox ejecuta exactamente una vez.
 * Hay una implementacion, la que reparte por fuente; la logica va en los {@link ActivitySourceAdapter}.
 */
public interface SourceEventHandler {

    void handle(SourceEnvelope envelope, SourceEventContext context);
}
