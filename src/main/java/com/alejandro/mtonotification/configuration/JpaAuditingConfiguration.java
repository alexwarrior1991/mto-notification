package com.alejandro.mtonotification.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * Configures Spring Data JPA auditing for persistence entities.
 *
 * <p>Estas columnas ({@code created_by} / {@code updated_by} y sus fechas) guardan el <b>último</b>
 * estado: quién tocó la fila la última vez. Aquí no hay Envers: el registro de actividad es
 * append-only y su historia es él mismo.</p>
 *
 * <p>Quién escribe lo decide {@link AuditActorResolver}. El razonamiento sobre {@code system}
 * frente a {@code unknown} está allí.</p>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@ConditionalOnProperty(name = "spring.data.jpa.auditing.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAuditingConfiguration {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(AuditActorResolver.currentActor());
    }
}
