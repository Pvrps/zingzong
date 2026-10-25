package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * One thread's stack of open spans and the trace they belong to. A span pushed on an empty stack
 * starts a trace, recording the inbound handoff as its parent when there is one; popping the
 * last span completes the trace. Every push publishes the active span through the WorkContext
 * so work handed to another thread can name its cause.
 */
final class SpanStack {

    private final Deque<OpenSpan> open = new ArrayDeque<>();
    private OpenTrace trace;
    private long nextToken = 1;

    boolean isEmpty() {
        return open.isEmpty();
    }

    OpenSpan top() {
        return open.peek();
    }

    OpenTrace trace() {
        return trace;
    }

    /** The nearest open span whose facts are of the type, innermost first; empty when there is none. */
    <F extends SpanFacts> Optional<F> nearestFacts(Class<F> type) {
        return open.stream().map(span -> span.facts).filter(type::isInstance).map(type::cast).findFirst();
    }

    OpenSpan push(Recording recording, SpanRecorder<?> recorder, SpanFacts facts, long nowNanos, WorkContextPropagation propagation) {
        if (open.isEmpty() || trace == null || trace.recording != recording) {
            // A recording that changed under an open stack (start or stop mid-call) abandons the stale spans.
            open.clear();
            Optional<WorkContextPropagation.Inbound> inbound = propagation.inbound();
            trace = new OpenTrace(recording, nowNanos,
                    inbound.map(cause -> cause.traceId).orElse(null),
                    inbound.map(cause -> cause.spanId).orElse(null));
        }
        OpenSpan parent = open.peek();
        int order = trace.nextOrder();
        OpenSpan span = new OpenSpan(OpenTrace.spanId(order), parent, order, nextToken++, recorder, facts, nowNanos - trace.startNanos, nowNanos);
        open.push(span);
        propagation.publish(trace.id, span.id);
        return span;
    }

    /** Pops the top span into the trace; returns the finished trace when it was the root, else empty. */
    Optional<Trace> pop(Span completed, String serverName, List<TraceInfo.Deployment> deployments, WorkContextPropagation propagation) {
        if (open.isEmpty() || trace == null) {
            return Optional.empty();
        }
        open.pop();
        trace.add(completed);
        if (!open.isEmpty()) {
            propagation.publish(trace.id, open.peek().id);
            return Optional.empty();
        }
        Trace finished = trace.build(serverName, deployments);
        trace = null;
        propagation.clear();
        return Optional.of(finished);
    }

    /** Abandons every open span; the next push starts a fresh trace. */
    void reset() {
        open.clear();
        trace = null;
    }
}
