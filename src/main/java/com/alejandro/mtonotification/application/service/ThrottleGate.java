package com.alejandro.mtonotification.application.service;

import java.time.Duration;

/** Un disparo por regla, clave y ventana, decidido en la base de datos. */
public interface ThrottleGate {

    /** @return {@code true} si esta llamada dispara; {@code false} si el freno sigue puesto. */
    boolean tryAcquire(String ruleKey, String dimensionKey, Duration window);
}
