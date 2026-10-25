package ca.gc.cra.xz02.devops.wltrace.agent.fixtures;

import java.time.Instant;

/**
 * Deliberately not Serializable and without a no-arg constructor: a fixture for the
 * capture serializer, which must capture it reflectively and flag it as not transportable.
 */
public final class AuditTrail {

    private final StringBuilder entries = new StringBuilder();
    private final Object lock = new Object();
    private final Instant openedAt;
    private int entryCount;

    public AuditTrail(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public void record(String entry) {
        synchronized (lock) {
            entries.append(Instant.now()).append(' ').append(entry).append('\n');
            entryCount++;
        }
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public int getEntryCount() {
        synchronized (lock) {
            return entryCount;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "AuditTrail[opened " + openedAt + ", " + entryCount + " entries]";
        }
    }
}
