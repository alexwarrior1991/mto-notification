package com.alejandro.mtonotification.application.dto.delivery;

/**
 * Una persona con direccion para un canal.
 *
 * @param username el nombre de usuario en el realm
 * @param address  la direccion del canal (el correo); {@code null} si no tiene
 */
public record Recipient(String username, String address) {

    public boolean hasAddress() {
        return address != null && !address.isBlank();
    }
}
