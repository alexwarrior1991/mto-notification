package com.alejandro.mtonotification.application.dto.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * El sobre comun de todo lo que llega por RabbitMQ: el {@code AsynchronousMessage} de
 * mto-configuration con las dos claves opcionales que los productores nuevos anaden
 * ({@code actor} y {@code correlationId}). {@code data} llega como mapa abierto: cada adaptador
 * lee lo que su fuente publica ({@code entityName}/{@code entityId}/{@code operation}/{@code values}
 * en datos maestros; {@code eventName} en los eventos de dominio), y una clave nueva no rompe nada.
 *
 * @param operationId   identificador de la operacion en origen; la clave de idempotencia
 * @param referenceId   referencia legible del agregado
 * @param origin        servicio emisor
 * @param creationDate  cuando el emisor creo el mensaje
 * @param eventType     {@code MASTER_DATA_STATION_UPDATED}, {@code MAINTENANCE_ORDER_CREATED}...
 * @param data          el payload de negocio
 * @param messageHash   huella en origen; no es una firma
 * @param actor         quien, si el emisor lo dice
 * @param correlationId la peticion o el trabajo que lo causo, si el emisor lo dice
 */
public record SourceEnvelope(
        UUID operationId,
        String referenceId,
        String origin,
        Instant creationDate,
        String eventType,
        Map<String, Object> data,
        String messageHash,
        SourceActor actor,
        String correlationId
) {

    public SourceEnvelope {
        data = data == null ? null : Map.copyOf(data);
    }
}
