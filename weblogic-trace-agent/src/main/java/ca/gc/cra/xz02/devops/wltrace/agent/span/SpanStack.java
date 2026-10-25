package ca.gc.cra.xz02.devops.wltrace.agent.span;

import ca.gc.cra.xz02.devops.wltrace.agent.meta.BeanMeta;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread stack of open spans and the trace they belong to. A span pushed on an empty
 * stack starts a trace, recording the inbound handoff as its parent when there is one;
 * popping the last span completes the trace.
 */
final class SpanStack {

    private static final ThreadLocal<SpanStack> CURRENT = new ThreadLocal<SpanStack>() {
        @Override
        protected SpanStack initialValue() {
            return new SpanStack();
        }
    };

    private final Deque<OpenSpan> open = new ArrayDeque<OpenSpan>();
    private TraceBuilder trace;

    static SpanStack current() {
        return CURRENT.get();
    }

    boolean isEmpty() {
        return open.isEmpty();
    }

    OpenSpan top() {
        return open.peek();
    }

    /** The nearest open servlet span's capture state, or null. */
    Object servletCapture() {
        for (OpenSpan span : open) {
            if (span.isServlet()) {
                return span.http;
            }
        }
        return null;
    }

    TraceBuilder trace() {
        return trace;
    }

    OpenSpan push(Recording recording, String kind, String layer, String beanClass, String method, long nowNanos,
                  Object http, WorkContextPropagation propagation) {
        return push(recording, kind, layer, beanClass, method, nowNanos, http, null, null, null, null, null, propagation);
    }

    OpenSpan push(Recording recording, String kind, String layer, String beanClass, String method, long nowNanos,
                  Object http, Object container, BeanMeta meta, String interfaceName,
                  String invocation, String transactionAttribute, WorkContextPropagation propagation) {
        if (open.isEmpty() || trace == null || trace.recording != recording) {
            // A recording that changed under an open stack (start/stop mid-call) abandons the stale spans.
            open.clear();
            WorkContextPropagation.Inbound inbound = propagation.inbound();
            trace = new TraceBuilder(recording, nowNanos, inbound == null ? null : inbound.traceId,
                    inbound == null ? null : inbound.spanId);
        }
        OpenSpan parent = open.peek();
        int order = trace.nextOrder();
        OpenSpan span = new OpenSpan(trace.spanId(order), parent, order, kind, layer, beanClass, method,
                nowNanos - trace.startNanos, nowNanos, http, container, meta, interfaceName, invocation, transactionAttribute);
        open.push(span);
        propagation.publish(trace.id, span.id);
        return span;
    }

    /** Pops the top span into the trace and returns the finished {@link Trace} when it was the root, else null. */
    Trace pop(Span completed, WorkContextPropagation propagation) {
        if (open.isEmpty() || trace == null) {
            return null;
        }
        open.pop();
        trace.add(completed);
        if (!open.isEmpty()) {
            propagation.publish(trace.id, open.peek().id);
            return null;
        }
        Trace finished = trace.build();
        trace = null;
        propagation.clear();
        return finished;
    }

    void reset() {
        open.clear();
        trace = null;
    }
}
