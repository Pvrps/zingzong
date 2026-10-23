package io.capture.agent.span;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/** An active start/stop window. Immutable identity, monotonic trace sequence. */
public final class Recording {

    public final String id;
    public final String name;
    public final long startedAtMillis;
    private final AtomicLong seq = new AtomicLong();

    Recording(String name) {
        this.name = name == null || name.trim().isEmpty() ? "unnamed" : name.trim();
        this.startedAtMillis = System.currentTimeMillis();
        this.id = "rec-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(startedAtMillis));
    }

    public long nextSeq() {
        return seq.incrementAndGet();
    }

    public long traceCount() {
        return seq.get();
    }
}
