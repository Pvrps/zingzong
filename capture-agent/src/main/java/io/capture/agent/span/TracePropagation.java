package io.capture.agent.span;

/**
 * Carries "which trace and span caused this" across a thread handoff inside one server:
 * an {@code @Asynchronous} call, a JMS message consumed by an MDB. {@link SpanStack} calls
 * {@link #publish} whenever the active span changes and {@link #clear} when the trace ends;
 * a span opening on an empty stack asks {@link #inbound} and, when something is there,
 * the new trace records it as {@code parentTraceId} / {@code parentSpanId}.
 * Cross-server propagation is out of scope; see docs/trace-propagation.md.
 */
public interface TracePropagation {

    /** What a handoff carried in: trace id and causing span id, or null. */
    final class Inbound {
        public final String traceId;
        public final String spanId;

        public Inbound(String traceId, String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
        }
    }

    Inbound inbound();

    /** The active span of the thread's trace changed (a push or a pop): make it visible to handoffs. */
    void publish(String traceId, String spanId);

    /** The trace completed on this thread. */
    void clear();

    TracePropagation NONE = new TracePropagation() {
        @Override
        public Inbound inbound() {
            return null;
        }

        @Override
        public void publish(String traceId, String spanId) {
        }

        @Override
        public void clear() {
        }
    };
}
