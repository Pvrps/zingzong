package com.acme.common.model;

import java.time.Instant;

/**
 * Deliberately not Serializable and without a no-arg constructor: a fixture for the
 * capture serializer, which must degrade to a placeholder instead of failing the call.
 */
public final class AuditTrail {

    private final StringBuilder entries = new StringBuilder();
    private final Object lock = new Object();
    private final Instant openedAt;

    public AuditTrail(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public void record(String entry) {
        synchronized (lock) {
            entries.append(Instant.now()).append(' ').append(entry).append('\n');
        }
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "AuditTrail[opened " + openedAt + ", " + entries.length() + " chars]";
        }
    }
}
