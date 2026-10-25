package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.Span;

/**
 * Entry and exit of JAX-RS resource and JAX-WS endpoint methods, called from
 * {@code ResourceAdvice}: a child HTTP span with layer "jaxrs" or "jaxws", arguments and result
 * captured like an EJB call.
 */
public final class ResourceRecorder {

    private ResourceRecorder() {
    }

    /** Returns 0 when nothing is recorded, else the token {@link #exit} needs. */
    public static long enter(Object self, String resourceClass, String method) {
        Recording recording = Spans.active();
        if (recording == null) {
            return 0L;
        }
        try {
            String layer = ResourceLayers.layerOf(self == null ? null : self.getClass());
            OpenSpan span = Spans.push(recording, Span.KIND_HTTP, layer, resourceClass, method, null, null, null, null, null);
            return Spans.token(span);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exit(long token, Object self, String resourceClass, String method, String descriptor,
                            Object[] args, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || !resourceClass.equals(open.beanClass) || !method.equals(open.method)) {
                // The recording boundary was crossed mid-trace: abandon this thread's state.
                stack.reset();
                return;
            }
            Span span = open.toSpan(System.nanoTime() - open.enteredAtNanos);
            Class<?> type = MethodSignatures.beanClass(self, resourceClass);
            Spans.fill(span, MethodSignatures.resolve(type, method, descriptor), args, result, thrown);
            Spans.complete(span);
        } catch (Throwable t) {
            Spans.abandon("resource exit handling for " + resourceClass + "." + method, t);
        }
    }
}
