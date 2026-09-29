package com.alejandro.mtonotification.domain.model;

/**
 * Sobre que trata un evento: la entidad de la fuente.
 *
 * @param type  el nombre logico de la entidad en la fuente ({@code user}, {@code order}, {@code profile})
 * @param id    su identificador en la fuente, siempre como texto
 * @param label algo legible para pintar ({@code MO-000123}, un nombre de usuario), si se sabe
 */
public record Subject(String type, String id, String label) {

    public Subject {
        type = DomainValidations.trimToNull(type);
        id = DomainValidations.trimToNull(id);
        label = DomainValidations.trimToNull(label);
    }

    public static Subject of(String type, String id) {
        return new Subject(type, id, null);
    }

    public static Subject of(String type, String id, String label) {
        return new Subject(type, id, label);
    }

    public static Subject none() {
        return new Subject(null, null, null);
    }
}
