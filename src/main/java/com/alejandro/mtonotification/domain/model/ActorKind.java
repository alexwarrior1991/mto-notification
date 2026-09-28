package com.alejandro.mtonotification.domain.model;

/**
 * Quien esta detras de un evento. Coincide con el tipo {@code actor_kind} de PostgreSQL.
 */
public enum ActorKind {

    /** Una persona, identificada por su nombre de usuario en el realm. */
    PERSON,

    /** Una cuenta de servicio de otro servicio del dominio ({@code service-account-*}). */
    SERVICE,

    /** Nadie en concreto: un planificador, una rafaga cerrada, un evento derivado. */
    SYSTEM
}
