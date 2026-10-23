package io.capture.agent.span;

/**
 * Extension point for cross-server trace stitching (T3 remote calls, JMS). Not implemented
 * yet; see docs/trace-propagation.md for the design. {@link SpanStack} calls it at the two
 * places where a trace id enters or leaves a thread.
 */
public interface TracePropagation {

    /**
     * Called when a span opens on an empty stack. Returns a trace id carried in from another
     * server (via WorkContext) that this new trace should reference as parentTraceId, or null.
     */
    String inboundTraceId();

    /** Called once a root span has opened a trace: make the id visible to outbound calls. */
    void publish(String traceId);

    /** Called when the trace completes on this thread. */
    void clear();

    TracePropagation NONE = new TracePropagation() {
        @Override
        public String inboundTraceId() {
            return null;
        }

        @Override
        public void publish(String traceId) {
        }

        @Override
        public void clear() {
        }
    };
}
