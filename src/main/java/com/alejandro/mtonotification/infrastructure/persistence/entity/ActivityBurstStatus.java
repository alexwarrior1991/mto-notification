package com.alejandro.mtonotification.infrastructure.persistence.entity;

/** Una rafaga esta abierta mientras recibe eventos; cerrada, ya es una linea del registro. */
public enum ActivityBurstStatus {
    OPEN,
    CLOSED
}
