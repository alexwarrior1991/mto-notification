package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.delivery.Recipient;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Las transacciones cortas del despachador, una por paso, para que el envio quede fuera. */
public interface DeliveryRelayService {

    List<Delivery> claimDue(int batchSize);

    Optional<Notification> loadNotification(Delivery delivery);

    /** Expande una AUDIENCE en filas RECIPIENT y la deja SENT; devuelve cuantas filas nuevas. */
    int expand(Delivery audienceDelivery, List<Recipient> recipients);

    void markSent(Delivery delivery);

    void reschedule(Delivery delivery, Instant nextAttemptAt, String error);

    /** Agotados los intentos: FAILED, y una linea {@code system.delivery.dead} en el registro. */
    void markDead(Delivery delivery, String error);

    void markSkipped(Delivery delivery, String reason);

    boolean isOverHourlyLimit(String channel, String address, int maxPerHour);
}
