package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.domain.model.Audience;

import java.util.List;

/**
 * De una audiencia a sus personas con direccion, para los canales que empujan. Se resuelve fuera
 * de la transaccion de ingesta: el directorio esta al otro lado de la red.
 */
public interface AudienceResolver {

    /**
     * @throws com.alejandro.mtonotification.application.exception.DirectoryUnavailableException si el directorio no responde
     */
    List<Recipient> resolve(Audience audience);
}
