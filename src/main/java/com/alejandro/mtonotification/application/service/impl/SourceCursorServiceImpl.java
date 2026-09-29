package com.alejandro.mtonotification.application.service.impl;

import com.alejandro.mtonotification.application.service.SourceCursorService;
import com.alejandro.mtonotification.configuration.keycloak.KeycloakProperties;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceCursor;
import com.alejandro.mtonotification.infrastructure.persistence.entity.SourceKind;
import com.alejandro.mtonotification.infrastructure.persistence.repository.SourceCursorRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** El arrendamiento por recuento de filas; el dueno es esta instancia (host y un sufijo unico). */
@Service
class SourceCursorServiceImpl implements SourceCursorService {

    private final SourceCursorRepository repository;
    private final EntityManager entityManager;
    private final KeycloakProperties properties;
    private final String owner;

    SourceCursorServiceImpl(SourceCursorRepository repository, EntityManager entityManager, KeycloakProperties properties) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.properties = properties;
        this.owner = hostname() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    @Transactional
    public Optional<Lease> acquire(SourceKind kind) {
        int acquired = repository.acquireLease(kind.name(), owner, Instant.now().plus(properties.events().leaseTtl()));
        if (acquired == 0) {
            return Optional.empty();
        }
        entityManager.clear();
        SourceCursor cursor = repository.findById(kind).orElseThrow(() -> new IllegalStateException("No cursor row for " + kind));
        return Optional.of(new Lease(kind, owner, cursor.getLastEventTime(), cursor.getLastSuccessAt()));
    }

    @Override
    @Transactional
    public void release(Lease lease, Instant lastEventTime, String error) {
        repository.release(lease.kind().name(), lease.owner(), lastEventTime, error);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceCursor> all() {
        return repository.findAll();
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException unknown) {
            return "unknown-host";
        }
    }
}
