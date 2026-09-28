package com.alejandro.mtonotification.configuration.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del identificador de correlación ({@code app.correlation.*}).
 *
 * <p>El nombre de la cabecera es configurable porque no está estandarizado, pero por defecto es el
 * mismo que usa el gateway ({@code X-Correlation-Id}), que es quien lo genera cuando el cliente no
 * lo trae. {@code maxLength} acota un valor entrante: 64 caracteres cubren de sobra un UUID (36) o
 * un {@code trace-id} de W3C (32); el límite existe para que nadie pueda hacer que el servicio
 * escriba cien kilobytes por línea de log.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.correlation")
public record CorrelationIdProperties(
        @DefaultValue("X-Correlation-Id") @NotBlank String headerName,
        @DefaultValue("64") @Min(8) @Max(128) int maxLength
) {
}
