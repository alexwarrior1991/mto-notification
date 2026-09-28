package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.domain.model.NotificationRule;

import java.util.List;
import java.util.Map;

/** Las reglas cargadas. Hoy un YAML; manana, una tabla editable por la API, con la misma interfaz. */
public interface RuleRepository {

    List<NotificationRule> rules();

    /** Las variables con las que se evaluan ({@code vars['x']} en las expresiones). */
    Map<String, Object> variables();

    /** De donde salieron, para la administracion. */
    String source();
}
