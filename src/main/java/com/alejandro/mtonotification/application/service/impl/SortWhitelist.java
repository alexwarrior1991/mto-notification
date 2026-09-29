package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.exception.InvalidSortException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Lo que cada recurso admite en {@code sort}: una propiedad de la entidad que no este aqui es un
 * 400 igual que una que no exista, y el tamano de pagina no pasa del tope.
 */
final class SortWhitelist {

    private SortWhitelist() {
    }

    static Pageable sanitize(Pageable pageable, Set<String> allowed, int maxPageSize) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidSortException(order.getProperty());
            }
        }
        if (pageable.getPageSize() > maxPageSize) {
            return PageRequest.of(pageable.getPageNumber(), maxPageSize, pageable.getSort());
        }
        return pageable;
    }
}
