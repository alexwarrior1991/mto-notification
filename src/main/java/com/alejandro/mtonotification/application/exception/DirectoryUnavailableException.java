package com.alejandro.mtonotification.application.exception;

/** 503: el directorio (la Admin API de Keycloak) no responde o el circuito esta abierto. */
public class DirectoryUnavailableException extends BusinessException {

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public DirectoryUnavailableException(String message) {
        super(message);
    }
}
