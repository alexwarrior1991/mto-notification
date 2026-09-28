package com.alejandro.mtonotification.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Las once entidades, todas en su paquete y con su tabla: una que se anada sin migracion no arranca, y esta lista lo recuerda. */
class JpaEntityModelTest {

    private static final String ENTITY_PACKAGE = "com.alejandro.mtonotification.infrastructure.persistence.entity";

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "inbox_message", "activity_event", "activity_burst", "notification", "notification_audience",
            "notification_receipt", "inbox_state", "delivery", "rule_throttle", "source_cursor");

    @Test
    void everyEntityLivesInTheEntityPackageAndMapsOneOfTheTenTables() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> tables = new java.util.TreeSet<>();
        for (var definition : scanner.findCandidateComponents("com.alejandro.mtonotification")) {
            Class<?> entity = Class.forName(definition.getBeanClassName());
            assertEquals(ENTITY_PACKAGE, entity.getPackageName(), entity.getName());
            Table table = entity.getAnnotation(Table.class);
            assertNotNull(table, entity.getName() + " needs @Table");
            tables.add(table.name());
        }
        assertEquals(EXPECTED_TABLES, tables);
    }

    @Test
    void thereIsNoEnversOnPurpose() {
        assertTrue(Thread.currentThread().getContextClassLoader().getResource("org/hibernate/envers/Audited.class") == null,
                "el registro es append-only y su historia es el mismo: sin Envers");
    }

    @Test
    void theExpectedTablesAreExactlyTheTen() {
        assertEquals(10, EXPECTED_TABLES.stream().map(String::trim).collect(Collectors.toSet()).size());
    }
}
