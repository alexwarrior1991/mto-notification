package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.exception.RuleDefinitionException;
import com.alejandro.mtonotification.application.service.RuleRepository;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import com.alejandro.mtonotification.domain.model.ActivitySeverity;
import com.alejandro.mtonotification.domain.model.EventTypeMatcher;
import com.alejandro.mtonotification.domain.model.NotificationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Las reglas del YAML ({@code app.notification.rules-location}), leidas y validadas al arrancar:
 * una clave repetida, un tipo de evento que no existe, una audiencia o un canal desconocidos
 * impiden arrancar. Es la unica implementacion hoy; editar por API sera otra sobre una tabla.
 */
@Service
public class YamlRuleRepository implements RuleRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(YamlRuleRepository.class);

    private final List<NotificationRule> rules;
    private final Map<String, Object> variables;
    private final String source;

    @Autowired
    public YamlRuleRepository(ResourceLoader resourceLoader, NotificationProperties properties) {
        this(resourceLoader.getResource(properties.rulesLocation()), properties.rules().variables());
    }

    public YamlRuleRepository(Resource resource, Map<String, Object> variableOverrides) {
        this.source = resource.getDescription();
        Map<String, Object> document = load(resource);
        Map<String, Object> fileVariables = new LinkedHashMap<>(PayloadReader.of(document).map("variables"));
        fileVariables.putAll(variableOverrides == null ? Map.of() : variableOverrides);
        this.variables = Map.copyOf(fileVariables);
        this.rules = parseRules(document);
        LOGGER.info("Notification rules loaded from {}: {} rule(s), {} variable(s)", source, rules.size(), variables.size());
    }

    @Override
    public List<NotificationRule> rules() {
        return rules;
    }

    @Override
    public Map<String, Object> variables() {
        return variables;
    }

    @Override
    public String source() {
        return source;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Resource resource) {
        if (!resource.exists()) {
            throw new RuleDefinitionException("Notification rules not found at " + resource.getDescription());
        }
        try (InputStream input = resource.getInputStream()) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (document == null) {
                return Map.of();
            }
            if (!(document instanceof Map<?, ?> map)) {
                throw new RuleDefinitionException("Notification rules at " + resource.getDescription() + " are not a YAML object");
            }
            return (Map<String, Object>) map;
        } catch (IOException exception) {
            throw new RuleDefinitionException("Cannot read the notification rules at " + resource.getDescription(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<NotificationRule> parseRules(Map<String, Object> document) {
        Object rulesNode = document.get("rules");
        if (rulesNode == null) {
            return List.of();
        }
        if (!(rulesNode instanceof List<?> list)) {
            throw new RuleDefinitionException("'rules' must be a list");
        }
        List<NotificationRule> parsed = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new RuleDefinitionException("Every rule must be a YAML object");
            }
            NotificationRule rule = parseRule((Map<String, Object>) map);
            if (!keys.add(rule.key())) {
                throw new RuleDefinitionException("Rule key '" + rule.key() + "' is defined twice");
            }
            parsed.add(rule);
        }
        return List.copyOf(parsed);
    }

    private static NotificationRule parseRule(Map<String, Object> map) {
        PayloadReader reader = PayloadReader.of(map);
        String key = reader.string("key");
        try {
            List<String> patterns = new ArrayList<>(reader.stringList("event"));
            if (patterns.isEmpty() && reader.string("event") != null) {
                patterns.add(reader.string("event"));
            }
            patterns.addAll(reader.stringList("events"));
            EventTypeMatcher matcher = EventTypeMatcher.of(patterns);

            String severityText = reader.string("severity");
            ActivitySeverity severity = severityText == null ? null : ActivitySeverity.valueOf(severityText.trim().toUpperCase());

            NotificationRule.Throttle throttle = null;
            Map<String, Object> throttleNode = reader.map("throttle");
            if (!throttleNode.isEmpty()) {
                PayloadReader throttleReader = PayloadReader.of(throttleNode);
                String window = throttleReader.string("window");
                if (window == null) {
                    throw new RuleDefinitionException("Rule '" + key + "': throttle needs a window");
                }
                Duration duration = DurationStyle.detectAndParse(window);
                throttle = new NotificationRule.Throttle(duration, throttleReader.string("key"));
            }

            return new NotificationRule(key, matcher, reader.string("when"), severity, reader.stringList("audiences"),
                    reader.stringList("channels"), reader.string("title"), reader.string("body"), reader.string("link"), throttle);
        } catch (IllegalArgumentException invalid) {
            throw new RuleDefinitionException("Rule '" + key + "' is invalid: " + invalid.getMessage(), invalid);
        }
    }
}
