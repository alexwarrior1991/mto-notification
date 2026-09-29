package com.alejandro.mtonotification.domain.model;

import java.util.Set;

/**
 * Los canales por los que sale un aviso. {@link #INBOX} es la bandeja, que no se entrega: se lee.
 * Los demas empujan y dejan una fila en {@code delivery}. En la base de datos el canal es un
 * {@code varchar} validado contra esta lista: un {@code push} para {@code mto-field} es una
 * constante mas y una implementacion mas de {@code DeliveryChannel}.
 */
public final class DeliveryChannels {

    public static final String INBOX = "inbox";
    public static final String EMAIL = "email";

    /** Los que una regla puede nombrar hoy. */
    public static final Set<String> KNOWN = Set.of(INBOX, EMAIL);

    private DeliveryChannels() {
    }

    public static boolean isKnown(String channel) {
        return channel != null && KNOWN.contains(channel.trim().toLowerCase());
    }

    /** Los que dejan una fila en {@code delivery}: todos menos la bandeja. */
    public static boolean isPushed(String channel) {
        return isKnown(channel) && !INBOX.equals(channel.trim().toLowerCase());
    }
}
