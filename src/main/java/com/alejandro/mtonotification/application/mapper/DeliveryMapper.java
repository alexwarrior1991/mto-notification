package com.alejandro.mtonotification.application.mapper;

import com.alejandro.mtonotification.application.dto.delivery.DeliveryResponse;
import com.alejandro.mtonotification.infrastructure.persistence.entity.Delivery;
import org.mapstruct.Mapper;

@Mapper(config = MapStructCentralConfig.class)
public interface DeliveryMapper {

    DeliveryResponse toResponse(Delivery delivery);
}
