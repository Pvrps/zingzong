package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMeta;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ContainerInvocation;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WlsMetadata;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry and exit of {@code MDListener.onMessage}, called from {@code MessageAdvice}: the
 * listener's {@code MessageDrivenBeanInfo} names the bean class, in its own loader, and the
 * destination; the message is the call's one argument.
 */
public final class MessageRecorder {

    private static final AtomicBoolean warned = new AtomicBoolean();

    private MessageRecorder() {
    }

    /** Returns 0 when nothing is recorded, else the token {@link #exit} needs. */
    public static long enter(Object listener, Object message) {
        Recording recording = Spans.active();
        if (recording == null) {
            return 0L;
        }
        try {
            Object info = ContainerInvocation.fieldUp(listener, "info");
            String className = info == null ? null : String.valueOf(ContainerInvocation.call(info, "getBeanClassName"));
            ClassLoader loader = info == null ? null : (ClassLoader) ContainerInvocation.call(info, "getClassLoader");
            if (className == null || loader == null) {
                if (warned.compareAndSet(false, true)) {
                    AgentLog.warn("message hook: listener not recognised (" + (listener == null ? "null" : listener.getClass().getName()) + "); delivery not recorded");
                }
                return 0L;
            }
            Class<?> bean = Class.forName(className, false, loader);
            BeanMeta meta = WlsMetadata.of(bean, new WlsMetadata.Hint(null, info));
            Object destination = ContainerInvocation.call(info, "getDestinationName");
            MethodSignatures.MethodInfo onMessage = MethodSignatures.resolve(bean, "onMessage", "(Ljavax/jms/Message;)V");
            String txAttribute = ContainerInvocation.transactionAttribute(ContainerInvocation.call(info, "getOnMessageMethodDescriptor"), info);
            OpenSpan span = Spans.push(recording, Span.KIND_EJB, null, bean.getName(), "onMessage", message, meta,
                    "javax.jms.MessageListener", EjbInfo.INVOCATION_MESSAGE, txAttribute);
            span.destination = destination == null ? null : destination.toString();
            span.methodInfo = onMessage;
            return Spans.token(span);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exit(long token, Object message, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            OpenSpan open = Spans.top(message);
            if (open == null) {
                return;
            }
            Span span = open.toSpan(System.nanoTime() - open.enteredAtNanos);
            Spans.fill(span, open.methodInfo, new Object[]{message}, null, thrown);
            Spans.complete(span);
        } catch (Throwable t) {
            Spans.abandon("message exit handling", t);
        }
    }
}
