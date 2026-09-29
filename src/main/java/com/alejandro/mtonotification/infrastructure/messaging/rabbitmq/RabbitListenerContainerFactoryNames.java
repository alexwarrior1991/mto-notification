package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

/** El nombre de la factory viaja como cadena en {@code @RabbitListener}; con la constante, un renombrado arrastra los dos sitios. */
public final class RabbitListenerContainerFactoryNames {

    /** La factory de todas las fuentes: mismo convertidor, mismos reintentos, nunca reencolar. */
    public static final String SOURCES = "sourceRabbitListenerContainerFactory";

    private RabbitListenerContainerFactoryNames() {
    }
}
