package com.alejandro.mtonotification.infrastructure.messaging.rabbitmq;

/** Las cabeceras AMQP que ponen los emisores del dominio (el outbox de mto-configuration y sus copias). */
public final class SourceMessageHeaders {

    public static final String EVENT_TYPE = "eventType";
    public static final String AGGREGATE_TYPE = "aggregateType";
    public static final String AGGREGATE_ID = "aggregateId";
    public static final String SEQUENCE_NUMBER = "sequenceNumber";
    public static final String SIGNATURE = "messageSignature";
    public static final String SIGNATURE_ALGORITHM = "messageSignatureAlgorithm";

    private SourceMessageHeaders() {
    }
}
