package com.alejandro.mtonotification.application.service;

/**
 * Pide un despacho en cuanto se confirme la transaccion en curso (o ya, si no hay ninguna): la
 * ingesta que acaba de escribir entregas no espera al planificador.
 */
public interface DeliveryDispatchTrigger {

    void requestDispatchAfterCommit();
}
