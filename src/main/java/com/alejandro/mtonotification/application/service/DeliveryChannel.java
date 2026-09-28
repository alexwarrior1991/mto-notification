package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;

/**
 * Un canal que empuja: el correo hoy, push manana. Se registran por {@link #channel()} y el
 * despachador busca el de cada entrega; sin implementacion para un canal, la entrega queda SKIPPED.
 */
public interface DeliveryChannel {

    /** {@code email}, {@code push}...: una de {@code DeliveryChannels}. */
    String channel();

    /**
     * Envia. Lo que lance cuenta como intento fallido y se reintenta con espera creciente.
     *
     * @throws Exception cualquier fallo del canal
     */
    void send(Notification notification, Recipient recipient) throws Exception;
}
