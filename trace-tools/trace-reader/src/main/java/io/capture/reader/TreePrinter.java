package io.capture.reader;

import io.capture.trace.Span;
import io.capture.trace.Trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Indented span tree with durations; {@code *} marks self-invocations, {@code !} failures. */
public final class TreePrinter {

    private TreePrinter() {
    }

    public static String print(Trace trace) {
        StringBuilder sb = new StringBuilder();
        sb.append("trace ").append(trace.trace.id);
        if (trace.recording != null) {
            sb.append(" seq=").append(trace.recording.seq);
        }
        sb.append(" startedAt=").append(trace.trace.startedAt)
                .append(" durationMs=").append(trace.trace.durationMs);
        if (trace.trace.principal != null) {
            sb.append(" principal=").append(trace.trace.principal);
        }
        if (trace.trace.httpSessionId != null) {
            sb.append(" httpSessionId=").append(trace.trace.httpSessionId);
        }
        if (trace.trace.parentTraceId != null) {
            sb.append(" parentTraceId=").append(trace.trace.parentTraceId);
        }
        sb.append('\n');

        Map<String, List<Span>> children = new HashMap<>();
        List<Span> roots = new ArrayList<>();
        for (Span span : trace.spans) {
            if (span.parentId == null) {
                roots.add(span);
            } else {
                children.computeIfAbsent(span.parentId, k -> new ArrayList<>()).add(span);
            }
        }
        Comparator<Span> byOrder = Comparator.comparingInt(s -> s.order);
        roots.sort(byOrder);
        for (List<Span> list : children.values()) {
            list.sort(byOrder);
        }
        for (Span root : roots) {
            print(root, children, 0, sb);
        }
        return sb.toString();
    }

    private static void print(Span span, Map<String, List<Span>> children, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(span.id).append(' ').append(Labels.span(span));
        sb.append(String.format(" %.3fms", span.durationNs / 1_000_000.0));
        if (Boolean.TRUE.equals(span.selfInvocation)) {
            sb.append(" *");
        }
        if (span.exception != null) {
            sb.append(" ! ").append(span.exception.className);
        }
        if (span.serializationWarnings != null && !span.serializationWarnings.isEmpty()) {
            sb.append(" (").append(span.serializationWarnings.size()).append(" warnings)");
        }
        sb.append('\n');
        for (Span child : children.getOrDefault(span.id, List.of())) {
            print(child, children, depth + 1, sb);
        }
    }
}
