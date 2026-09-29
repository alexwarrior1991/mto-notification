package com.alejandro.mtonotification.infrastructure.persistence.entity;

/**
 * {@link #AUDIENCE} la escribe la ingesta, una por (notificacion, canal, audiencia), y el
 * despachador la expande en filas {@link #RECIPIENT}, una por persona con direccion, que son las
 * que se envian. Coincide con {@code delivery_scope}.
 */
public enum DeliveryScope {
    AUDIENCE,
    RECIPIENT
}
