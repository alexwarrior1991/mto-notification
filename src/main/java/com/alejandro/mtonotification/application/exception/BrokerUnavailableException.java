package com.alejandro.mtonotification.application.exception;

/** 503 {@code SRC-503}: RabbitMQ no responde. Es transitorio y el cliente puede reintentar. */
public class BrokerUnavailableException extends BusinessException {

    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
