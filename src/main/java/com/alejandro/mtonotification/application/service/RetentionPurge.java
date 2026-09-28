package com.alejandro.mtonotification.application.service;

import java.util.Map;

/** Borra por lotes lo que ha caducado, tabla a tabla, con la retencion de cada una. */
public interface RetentionPurge {

    /** @return filas borradas por tabla o categoria. */
    Map<String, Integer> purge();
}
