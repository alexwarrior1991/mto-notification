package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.admin.BroadcastRequest;
import com.alejandro.mtonotification.application.dto.admin.RulesResponse;
import com.alejandro.mtonotification.application.dto.admin.SourceStatusResponse;
import com.alejandro.mtonotification.application.dto.admin.TestEmailRequest;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryFilter;
import com.alejandro.mtonotification.application.dto.delivery.DeliveryResponse;
import com.alejandro.mtonotification.application.dto.notification.NotificationResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Administracion: reglas cargadas, entregas y reintentos, estado de las fuentes, avisos manuales. */
public interface AdminService {

    RulesResponse rules();

    PageResponse<DeliveryResponse> deliveries(DeliveryFilter filter, Pageable pageable);

    /** Una FAILED o SKIPPED vuelve a la cola. 404 {@code DLV-404}; 409 {@code DLV-409} si no es reintentable. */
    DeliveryResponse retry(UUID deliveryId);

    SourceStatusResponse sources();

    /** Una notificacion {@code system.test-email} para quien lo pide, con una entrega directa a la direccion. */
    NotificationResponse sendTestEmail(TestEmailRequest request);

    /** Un aviso manual. 422 {@code NTF-422} con una clase de audiencia o un canal desconocidos. */
    NotificationResponse broadcast(BroadcastRequest request);
}
