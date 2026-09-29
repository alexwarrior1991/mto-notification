package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.ThrottleGate;
import com.alejandro.mtonotification.infrastructure.persistence.repository.RuleThrottleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/** El freno, decidido por el upsert condicional: fila afectada, dispara. */
@Service
@RequiredArgsConstructor
class ThrottleGateImpl implements ThrottleGate {

    private static final int MAX_DIMENSION_LENGTH = 300;

    private final RuleThrottleRepository ruleThrottleRepository;

    @Override
    @Transactional
    public boolean tryAcquire(String ruleKey, String dimensionKey, Duration window) {
        String dimension = dimensionKey == null || dimensionKey.isBlank() ? "*" : dimensionKey.trim();
        if (dimension.length() > MAX_DIMENSION_LENGTH) {
            dimension = dimension.substring(0, MAX_DIMENSION_LENGTH);
        }
        return ruleThrottleRepository.tryAcquire(ruleKey, dimension, Math.max(1, window.toSeconds())) == 1;
    }
}
