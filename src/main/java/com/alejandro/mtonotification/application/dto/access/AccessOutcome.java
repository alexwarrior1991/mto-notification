package com.alejandro.mtonotification.application.dto.access;

/** Como acabo un acceso: lo que separa un login de un fallo, un bloqueo o una racha. */
public enum AccessOutcome {
    SUCCESS,
    FAILURE
}
