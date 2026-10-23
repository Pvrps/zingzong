package io.capture.agent.span;

import io.capture.trace.Span;
import io.capture.trace.Trace;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-thread stack of open spans and the trace they belong to. A span pushed on an empty
 * stack starts a trace; popping the last span completes it. Nested calls on the same thread
 * (local EJB calls, and WebLogic's same-server remote path) form the tree naturally.
 *
 * Cross-server stitching hooks in through {@link TracePropagation}: an inbound trace id on
 * an empty stack becomes {@code parentTraceId}, and a newly opened trace id is published
 * for outbound calls. See docs/trace-propagation.md.
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

    TraceBuilder trace() {
        return trace;
    }

    OpenSpan push(Recording recording, String beanClass, String method, long nowNanos,
                  String transaction, TracePropagation propagation) {
        if (open.isEmpty() || trace == null || trace.recording != recording) {
            // New trace: either nothing is open, or the recording changed under an open stack
            // (start/stop mid-call); the stale spans are abandoned.
            open.clear();
            // EXTENSION POINT (cross-server stitching, docs/trace-propagation.md): a trace id
            // carried in by WorkContext becomes parentTraceId; our id is published for
            // outbound T3/JMS calls. TracePropagation.NONE until Phase 3 step 6 is implemented.
            String inbound = propagation.inboundTraceId();
            trace = new TraceBuilder(recording, nowNanos, inbound);
            propagation.publish(trace.id);
        }
        OpenSpan parent = open.peek();
        int order = trace.nextOrder();
        OpenSpan span = new OpenSpan(trace.spanId(order), parent, order, beanClass, method,
                nowNanos - trace.startNanos, nowNanos, transaction);
        open.push(span);
        return span;
    }

    /**
     * Pops the top span, adds its completed form to the trace, and returns the finished
     * {@link Trace} when this was the root, else null.
     */
    Trace pop(Span completed, TracePropagation propagation) {
        if (open.isEmpty() || trace == null) {
            return null;
        }
        open.pop();
        trace.add(completed);
        if (!open.isEmpty()) {
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
