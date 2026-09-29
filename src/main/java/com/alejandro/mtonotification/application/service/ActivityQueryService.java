package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.access.AccessEventFilter;
import com.alejandro.mtonotification.application.dto.access.AccessEventResponse;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventFilter;
import com.alejandro.mtonotification.application.dto.activity.ActivityEventResponse;
import com.alejandro.mtonotification.application.dto.common.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** El registro (todo menos los accesos) y los accesos, cada uno por su puerta. */
public interface ActivityQueryService {

    PageResponse<ActivityEventResponse> search(ActivityEventFilter filter, Pageable pageable);

    /** El detalle, con su payload. Un acceso responde 404 aqui: se lee por {@code /access}. */
    ActivityEventResponse get(UUID id);

    PageResponse<AccessEventResponse> searchAccess(AccessEventFilter filter, Pageable pageable);
}
