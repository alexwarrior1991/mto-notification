package com.alejandro.mtonotification.application.service.impl;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.util.Map;

/**
 * Lee {@code payload.loQueSea} de un mapa y devuelve {@code null} cuando la clave no esta, en vez
 * de fallar: los payloads los componen las fuentes y una clave puede faltar en un evento y estar en
 * el siguiente. Una condicion sobre una clave ausente es falsa, no un error. Solo lectura.
 */
final class LenientMapAccessor implements PropertyAccessor {

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return new Class<?>[]{Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target instanceof Map;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
        if (!(target instanceof Map<?, ?> map)) {
            throw new AccessException("Not a map: " + target);
        }
        return new TypedValue(map.get(name));
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
        throw new AccessException("Rule expressions are read-only");
    }
}
