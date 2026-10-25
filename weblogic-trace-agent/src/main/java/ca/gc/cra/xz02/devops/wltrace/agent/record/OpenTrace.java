package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.RecordingRef;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

/** The trace under construction on one thread: its identity, clock origin, caller, and the spans completed so far. */
final class OpenTrace {

    final Recording recording;
    final String id;
    final long startNanos;
    final long startedAtMillis;
    final String parentTraceId;
    final String parentSpanId;
    private final List<Span> spans = new ArrayList<>();
    private int nextOrder;
    private String principal;
    private String httpSessionId;
    private long endNs;

    OpenTrace(Recording recording, long startNanos, String parentTraceId, String parentSpanId) {
        this.recording = recording;
        this.id = "t-" + UUID.randomUUID().toString().substring(0, 8);
        this.startNanos = startNanos;
        this.startedAtMillis = System.currentTimeMillis();
        this.parentTraceId = parentTraceId;
        this.parentSpanId = parentSpanId;
    }

    int nextOrder() {
        return nextOrder++;
    }

    static String spanId(int order) {
        return "s" + (order + 1);
    }

    String principal() {
        return principal;
    }

    /** Sets the principal when none is known yet; a later, non-null value never replaces an earlier one. */
    void principal(String value) {
        if (principal == null) {
            principal = value;
        }
    }

    String httpSessionId() {
        return httpSessionId;
    }

    void httpSessionId(String value) {
        if (httpSessionId == null) {
            httpSessionId = value;
        }
    }

    void add(Span span) {
        spans.add(span);
        endNs = Math.max(endNs, span.startNs + span.durationNs);
    }

    /** The finished trace: identity, timing, caller, server and deployments, spans ordered by {@code order}. */
    Trace build(String serverName, List<TraceInfo.Deployment> deployments) {
        Trace trace = new Trace();
        trace.recording = new RecordingRef();
        trace.recording.id = recording.id;
        trace.recording.name = recording.name;
        trace.trace = new TraceInfo();
        trace.trace.id = id;
        trace.trace.startedAt = isoInstant(startedAtMillis);
        trace.trace.durationMs = endNs / 1000000L;
        trace.trace.principal = principal;
        trace.trace.httpSessionId = httpSessionId;
        trace.trace.serverName = serverName;
        trace.trace.parentTraceId = parentTraceId;
        trace.trace.parentSpanId = parentSpanId;
        trace.trace.deployments = deployments.isEmpty() ? null : new ArrayList<>(deployments);
        List<Span> ordered = new ArrayList<>(spans);
        ordered.sort(Comparator.comparingInt(span -> span.order));
        trace.spans = ordered;
        return trace;
    }

    static String isoInstant(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(millis);
    }
}
