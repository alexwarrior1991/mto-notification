package com.alejandro.mtonotification.application.dto.activity;

import com.alejandro.mtonotification.domain.model.ActorKind;

public record ActorResponse(ActorKind kind, String username, String id) {
}
