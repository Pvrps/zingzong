package ca.gc.cra.xz02.devops.wltrace.agent.record;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An active start/stop window. The id is assigned by whoever starts it, so that one recording
 * has one id on every server of a domain; this server's trace sequence is its own.
 */
public final class Recording {

    public final String id;
    public final String name;
    public final long startedAtMillis;
    private final AtomicLong sequence = new AtomicLong();

    Recording(String id, String name) {
        if (id == null || id.trim().isEmpty() || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("recording id must be a non-empty file name without separators, got: " + id);
        }
        this.id = id.trim();
        this.name = name == null || name.trim().isEmpty() ? "unnamed" : name.trim();
        this.startedAtMillis = System.currentTimeMillis();
    }

    public long nextSequence() {
        return sequence.incrementAndGet();
    }

    public long traceCount() {
        return sequence.get();
    }
}
