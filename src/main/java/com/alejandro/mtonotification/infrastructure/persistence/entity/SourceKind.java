package com.alejandro.mtonotification.infrastructure.persistence.entity;

/** Las fuentes que este servicio sondea (las de RabbitMQ empujan, y no tienen marca de agua). */
public enum SourceKind {
    KEYCLOAK_LOGIN,
    KEYCLOAK_ADMIN
}
