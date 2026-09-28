package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.DeliveryDispatchTrigger;
import com.alejandro.mtonotification.application.service.DeliveryDispatcher;
import com.alejandro.mtonotification.configuration.notification.NotificationProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * El despacho inmediato: cuando la transaccion que escribio entregas se confirma, un hilo propio
 * llama al despachador. Varias peticiones seguidas se funden en una (el despachador recoge todo
 * lo pendiente); con {@code immediate-dispatch=false} solo queda el planificador. El despachador
 * se pide al contexto al usarlo, no al construir: entre los dos hay un ciclo por el registro.
 */
@Service
class DeliveryDispatchTriggerImpl implements DeliveryDispatchTrigger {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryDispatchTriggerImpl.class);

    private final ObjectProvider<DeliveryDispatcher> dispatcher;
    private final NotificationProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "delivery-dispatch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean requested = new AtomicBoolean();

    DeliveryDispatchTriggerImpl(ObjectProvider<DeliveryDispatcher> dispatcher, NotificationProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public void requestDispatchAfterCommit() {
        NotificationProperties.Delivery delivery = properties.delivery();
        if (!delivery.enabled() || !delivery.immediateDispatch()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule();
                }
            });
        } else {
            schedule();
        }
    }

    private void schedule() {
        if (!requested.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            requested.set(false);
            try {
                DeliveryDispatcher target = dispatcher.getIfAvailable();
                if (target != null) {
                    target.dispatchDue();
                }
            } catch (RuntimeException failure) {
                LOGGER.error("Immediate delivery dispatch failed; the scheduled dispatcher will retry", failure);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
