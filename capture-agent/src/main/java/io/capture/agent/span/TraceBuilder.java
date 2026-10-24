package io.capture.agent.span;

import io.capture.trace.RecordingRef;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.agent.meta.WlsMetadata;
import io.capture.trace.TraceInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

/** The trace under construction on one thread: identity, clock origin, completed spans. */
final class TraceBuilder {

    final Recording recording;
    final String id;
    final long startNanos;
    final long startedAtMillis;
    final String parentTraceId;
    final String parentSpanId;
    private final List<Span> spans = new ArrayList<Span>();
    private int nextOrder;
    private String principal;
    private String httpSessionId;
    private long endNs;

    TraceBuilder(Recording recording, long startNanos, String parentTraceId, String parentSpanId) {
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

    String spanId(int order) {
        return "s" + (order + 1);
    }

    void principal(String value) {
        if (principal == null) {
            principal = value;
        }
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

    Trace build() {
        Trace trace = new Trace();
        trace.recording = new RecordingRef();
        trace.recording.id = recording.id;
        trace.recording.name = recording.name;
        trace.trace = new TraceInfo();
        trace.trace.id = id;
        trace.trace.startedAt = iso(startedAtMillis);
        trace.trace.durationMs = endNs / 1000000L;
        trace.trace.principal = principal;
        trace.trace.httpSessionId = httpSessionId;
        trace.trace.serverName = WlsRuntime.serverName();
        trace.trace.parentTraceId = parentTraceId;
        trace.trace.parentSpanId = parentSpanId;
        List<TraceInfo.Deployment> deployments = WlsMetadata.deployments();
        trace.trace.deployments = deployments.isEmpty() ? null : new ArrayList<TraceInfo.Deployment>(deployments);
        List<Span> ordered = new ArrayList<Span>(spans);
        Collections.sort(ordered, new Comparator<Span>() {
            @Override
            public int compare(Span a, Span b) {
                return a.order - b.order;
            }
        });
        trace.spans = ordered;
        return trace;
    }

    private static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
