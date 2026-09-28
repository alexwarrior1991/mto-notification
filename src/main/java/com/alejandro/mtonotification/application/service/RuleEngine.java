package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.infrastructure.persistence.entity.ActivityEvent;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Notification;

import java.util.List;
import java.util.Map;

/**
 * Para una linea recien escrita: que reglas casan, cual pasa su condicion y su freno, y que
 * notificaciones salen de ello. Corre en la transaccion de la ingesta.
 */
public interface RuleEngine {

    List<Notification> evaluate(ActivityEvent event, Map<String, Object> payload);
}
