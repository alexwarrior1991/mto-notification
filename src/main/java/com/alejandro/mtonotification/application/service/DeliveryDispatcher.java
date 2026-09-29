package com.alejandro.mtonotification.application.service;

/**
 * Reclama las entregas que tocan, expande las de audiencia en destinatarios y envia las de
 * destinatario por su canal. Sin transaccion propia: cada paso corto es del relay, y el envio, de
 * la red.
 */
public interface DeliveryDispatcher {

    DispatchSummary dispatchDue();

    /** Un lote: cuantas se reclamaron y en que acabaron. */
    record DispatchSummary(int claimed, int expanded, int sent, int rescheduled, int dead, int skipped) {

        public static DispatchSummary empty() {
            return new DispatchSummary(0, 0, 0, 0, 0, 0);
        }

        public boolean isEmpty() {
            return claimed == 0;
        }
    }
}
