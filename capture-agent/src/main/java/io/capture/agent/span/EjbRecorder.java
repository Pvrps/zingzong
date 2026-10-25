package io.capture.agent.span;

import io.capture.agent.AgentLog;
import io.capture.agent.meta.BeanMeta;
import io.capture.agent.meta.ContainerInvocation;
import io.capture.agent.meta.WlsMetadata;
import io.capture.trace.Span;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry and exit of WebLogic's session-bean invokers, called from {@code ContainerAdvice}: the
 * span's facts come from the container's invocation wrapper, client object and BeanInfo.
 */
public final class EjbRecorder {

    private static final AtomicBoolean warned = new AtomicBoolean();

    private EjbRecorder() {
    }

    /** Returns 0 when nothing is recorded, else the token {@link #exit} needs. */
    public static long enter(Object invokable, Object clientObject, Object wrapper) {
        Recording recording = Spans.active();
        if (recording == null) {
            return 0L;
        }
        try {
            ContainerInvocation ci = ContainerInvocation.read(invokable, clientObject, wrapper);
            if (ci == null || ci.beanClass == null) {
                if (warned.compareAndSet(false, true)) {
                    AgentLog.warn("container hook: invocation objects not recognised (" + describe(invokable) + ", "
                            + describe(clientObject) + ", " + describe(wrapper) + "); call not recorded");
                }
                return 0L;
            }
            BeanMeta meta = WlsMetadata.of(ci.beanClass, new WlsMetadata.Hint(ci.ejbName, ci.beanInfo));
            String invocation = ci.local ? BeanMeta.LOCAL : BeanMeta.REMOTE;
            String methodName = ci.method == null ? "?" : ci.method.getName();
            String iface = ci.businessInterface(meta == null ? null : meta.interfaces);
            OpenSpan span = Spans.push(recording, Span.KIND_EJB, null, ci.beanClass.getName(), methodName, ci, meta,
                    iface, invocation, ci.transactionAttribute);
            return Spans.token(span);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exit(long token, Object[] args, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || !(open.container instanceof ContainerInvocation)) {
                stack.reset();
                return;
            }
            ContainerInvocation ci = (ContainerInvocation) open.container;
            Span span = open.toSpan(System.nanoTime() - open.enteredAtNanos);
            MethodSignatures.MethodInfo info = ci.method == null
                    ? MethodSignatures.MethodInfo.unknown(open.method, "")
                    : MethodSignatures.resolve(ci.beanClass, ci.method);
            Spans.fill(span, info, args, result, thrown);
            Spans.complete(span);
        } catch (Throwable t) {
            Spans.abandon("container exit handling", t);
        }
    }

    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
