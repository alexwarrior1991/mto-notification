package com.alejandro.mtonotification.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Huella SHA-256 de un evento que no trae identificador (los de Keycloak no lo traen): se calcula
 * sobre sus campos en un orden fijo, de modo que dos lecturas del mismo evento dan la misma huella
 * y el inbox descarta la segunda.
 */
public final class Fingerprints {

    private static final String SEPARATOR = "|";

    private Fingerprints() {
    }

    /** Une las partes en orden con {@code |}; un {@code null} es la cadena vacia. */
    public static String canonical(Object... parts) {
        StringBuilder builder = new StringBuilder();
        for (Object part : parts) {
            if (!builder.isEmpty()) {
                builder.append(SEPARATOR);
            }
            builder.append(part == null ? "" : part.toString());
        }
        return builder.toString();
    }

    /** Un mapa en forma canonica: claves ordenadas, {@code k=v} separados por {@code ;}. */
    public static String canonical(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, ?> entry : new TreeMap<>(values).entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue() == null ? "" : entry.getValue());
        }
        return builder.toString();
    }

    public static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
