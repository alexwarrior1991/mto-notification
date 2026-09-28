package com.alejandro.mtonotification.application.service.impl;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.DataBindingPropertyAccessor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evalua las expresiones de las reglas con SpEL sobre un contexto de solo lectura: {@code event}
 * (la linea, como mapa), {@code payload} (lo que dijo la fuente) y {@code vars} (las variables).
 * Sin referencias a tipos ni a beans: una regla no puede ejecutar nada, solo mirar.
 *
 * <p>{@code when} es una expresion; los textos son plantillas con {@code #{...}}. Una clave ausente
 * en el payload vale {@code null}, no un error.</p>
 */
@Component
public class RuleExpressionEvaluator {

    public static final String EVENT = "event";
    public static final String PAYLOAD = "payload";
    public static final String VARS = "vars";

    private static final ParserContext TEMPLATE = new TemplateParserContext("#{", "}");

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> conditions = new ConcurrentHashMap<>();
    private final Map<String, Expression> templates = new ConcurrentHashMap<>();

    /** Una condicion que no devuelve {@code true} (incluido {@code null}) es falsa. */
    public boolean condition(String expression, Map<String, Object> scope) {
        Expression parsed = conditions.computeIfAbsent(expression, parser::parseExpression);
        Object value = parsed.getValue(context(scope));
        return Boolean.TRUE.equals(value);
    }

    /** Una plantilla sin {@code #{...}} es su propio texto. */
    public String render(String template, Map<String, Object> scope) {
        if (template == null) {
            return null;
        }
        Expression parsed = templates.computeIfAbsent(template, text -> parser.parseExpression(text, TEMPLATE));
        Object value = parsed.getValue(context(scope));
        return value == null ? "" : value.toString();
    }

    private static EvaluationContext context(Map<String, Object> scope) {
        return SimpleEvaluationContext
                .forPropertyAccessors(new LenientMapAccessor(), DataBindingPropertyAccessor.forReadOnlyAccess())
                .withInstanceMethods()
                .withRootObject(scope)
                .build();
    }
}
