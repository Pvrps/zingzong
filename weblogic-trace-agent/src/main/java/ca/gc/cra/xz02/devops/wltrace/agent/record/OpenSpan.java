package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.Span;

/** A span between its enter and its exit: identity, position in the trace, the facts read at enter, and the clock. */
final class OpenSpan {

    final String id;
    final String parentId;
    final int depth;
    final int order;
    /** Non-zero; what the exit advice hands back so the recorder knows it closes this span. */
    final long token;
    final SpanRecorder<?> recorder;
    final SpanFacts facts;
    /** Nanoseconds since the trace started. */
    final long startNs;
    final long enteredAtNanos;

    OpenSpan(String id, OpenSpan parent, int order, long token, SpanRecorder<?> recorder, SpanFacts facts, long startNs, long enteredAtNanos) {
        this.id = id;
        this.parentId = parent == null ? null : parent.id;
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.order = order;
        this.token = token;
        this.recorder = recorder;
        this.facts = facts;
        this.startNs = startNs;
        this.enteredAtNanos = enteredAtNanos;
    }

    /** The span's identity, position, kind, thread and timing; the kind's facts are added by {@link SpanFacts#describe}. */
    Span toSpan(long exitedAtNanos) {
        Span span = new Span();
        span.id = id;
        span.parentId = parentId;
        span.depth = depth;
        span.order = order;
        span.kind = recorder.kind();
        span.thread = Thread.currentThread().getName();
        span.startNs = startNs;
        span.durationNs = exitedAtNanos - enteredAtNanos;
        return span;
    }
}
