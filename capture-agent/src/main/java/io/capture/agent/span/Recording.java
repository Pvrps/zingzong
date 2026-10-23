package io.capture.agent.span;

import io.capture.trace.RecordingManifest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An active start/stop window. Immutable identity, monotonic trace sequence, optional
 * filters (for shared servers: only traces of this principal / HTTP session are recorded).
 */
public final class Recording {

    public final String id;
    public final String name;
    public final long startedAtMillis;
    /** Null when off. */
    public final String principalFilter;
    public final String httpSessionFilter;
    private final AtomicLong seq = new AtomicLong();

    private static String lastBase;
    private static int sameSecond;

    Recording(String name) {
        this(name, null, null);
    }

    Recording(String name, String principalFilter, String httpSessionFilter) {
        this.name = name == null || name.trim().isEmpty() ? "unnamed" : name.trim();
        this.startedAtMillis = System.currentTimeMillis();
        this.id = uniqueId("rec-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(startedAtMillis)));
        this.principalFilter = blankToNull(principalFilter);
        this.httpSessionFilter = blankToNull(httpSessionFilter);
    }

    public long nextSeq() {
        return seq.incrementAndGet();
    }

    public long traceCount() {
        return seq.get();
    }

    public boolean hasFilters() {
        return principalFilter != null || httpSessionFilter != null;
    }

    /** True when a root span with these facts belongs in this recording. */
    public boolean accepts(String principal, String httpSessionId) {
        if (principalFilter != null && !principalFilter.equals(principal)) {
            return false;
        }
        return httpSessionFilter == null || httpSessionFilter.equals(httpSessionId);
    }

    RecordingManifest.Filters filters() {
        if (!hasFilters()) {
            return null;
        }
        RecordingManifest.Filters filters = new RecordingManifest.Filters();
        filters.principal = principalFilter;
        filters.httpSessionId = httpSessionFilter;
        return filters;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** Ids have second resolution; two recordings within one second get a suffix. */
    private static synchronized String uniqueId(String base) {
        if (base.equals(lastBase)) {
            return base + "-" + (++sameSecond);
        }
        lastBase = base;
        sameSecond = 1;
        return base;
    }
}
