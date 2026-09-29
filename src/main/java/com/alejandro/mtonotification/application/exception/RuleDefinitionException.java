package com.alejandro.mtonotification.application.exception;

/** Una regla del YAML no vale. Solo se lanza al arrancar: la aplicacion no llega a servir nada. */
public class RuleDefinitionException extends RuntimeException {

    public RuleDefinitionException(String message) {
        super(message);
    }

    public RuleDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
