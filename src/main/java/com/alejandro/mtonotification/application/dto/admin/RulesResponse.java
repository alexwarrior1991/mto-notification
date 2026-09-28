package com.alejandro.mtonotification.application.dto.admin;

import java.util.List;
import java.util.Map;

/** Las reglas cargadas, de donde salieron y las variables con las que se evaluan. */
public record RulesResponse(String source, Map<String, Object> variables, List<RuleResponse> rules) {
}
