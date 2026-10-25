package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WlsMetadata;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WlsRuntime;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation;

import com.fasterxml.jackson.databind.JsonNode;
import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.http.HttpCapture;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMeta;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.SerializationContext;
import ca.gc.cra.xz02.devops.wltrace.model.ExceptionInfo;
import ca.gc.cra.xz02.devops.wltrace.model.HttpInfo;
import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.ValueCapture;

import java.util.ArrayList;
import java.util.List;

/**
 * What every kind of recorder shares: opening a span on the thread's stack, closing it with its
 * arguments, result or exception serialized, and handing a finished trace to the writer. Never
 * throws into the instrumented call.
 */
public final class Spans {

    private Spans() {
    }

    /** The active recording, or null when nothing is to be recorded. */
    public static Recording active() {
        return Recorder.active();
    }

    /** Pushes a span; a root span takes principal and HTTP session id from the server. Returns the open span. */
    static OpenSpan push(Recording recording, String kind, String layer, String className, String method,
                         Object container, BeanMeta meta, String interfaceName, String invocation, String transactionAttribute) {
        SpanStack stack = SpanStack.current();
        boolean root = stack.isEmpty();
        String principal = root ? WlsRuntime.principal() : null;
        String httpSessionId = root ? WlsRuntime.httpSessionId() : null;
        OpenSpan span = stack.push(recording, kind, layer, className, method, System.nanoTime(), null, container,
                meta, interfaceName, invocation, transactionAttribute, Recorder.propagation());
        if (root) {
            stack.trace().principal(principal);
            stack.trace().httpSessionId(httpSessionId);
        }
        return span;
    }

    /** The non-zero token an exit advice gets back to find its span. */
    static long token(OpenSpan span) {
        return span.enteredAtNanos == 0L ? 1L : span.enteredAtNanos;
    }

    /** The open span on top of the thread's stack whose {@code container} is the given object, else null after abandoning the stack. */
    static OpenSpan top(Object container) {
        SpanStack stack = SpanStack.current();
        OpenSpan open = stack.top();
        if (open == null || open.container != container) {
            stack.reset();
            return null;
        }
        return open;
    }

    /** Pops the finished span; when it was the root, hands the trace to the writer. */
    static void complete(Span span) {
        SpanStack stack = SpanStack.current();
        Recording recording = stack.trace().recording;
        Trace finished = stack.pop(span, Recorder.propagation());
        if (finished != null) {
            TraceWriter writer = Recorder.writer();
            if (writer != null) {
                writer.offer(recording, finished);
            }
        }
    }

    /** Abandons the thread's stack after an exit failed, so the next root starts clean. */
    static void abandon(String what, Throwable t) {
        try {
            SpanStack.current().reset();
            AgentLog.error(what + " failed", t);
        } catch (Throwable ignored) {
            // never into the business call
        }
    }

    /** Serializes arguments and result or exception into the span, with the method's signature. */
    static void fill(Span span, MethodSignatures.MethodInfo info, Object[] args, Object result, Throwable thrown) {
        if (span.ejb != null) {
            span.ejb.signature = info.signature;
        } else if (span.http != null) {
            span.http.resourceSignature = info.signature;
        }
        List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
        span.args = new ArrayList<ValueCapture>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                span.args.add(capture(args[i], info.parameterType(i), "$.args[" + i + "]", warnings));
            }
        }
        if (thrown != null) {
            span.exception = exception(thrown, warnings);
        } else if (!info.returnsVoid()) {
            span.result = capture(result, info.returnType == null ? Object.class : info.returnType, "$.result", warnings);
        }
        if (!warnings.isEmpty()) {
            span.serializationWarnings = warnings;
        }
    }

    static ExceptionInfo exception(Throwable thrown, List<SerializationWarning> warnings) {
        ExceptionInfo exception = new ExceptionInfo();
        exception.className = thrown.getClass().getName();
        exception.message = thrown.getMessage();
        exception.value = serialize(thrown, Throwable.class, "$.exception", warnings);
        return exception;
    }

    static ValueCapture capture(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        ValueCapture capture = new ValueCapture();
        capture.declaredType = declaredType.getName();
        capture.value = serialize(value, declaredType, path, warnings);
        return capture;
    }

    static JsonNode serialize(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        SerializationContext ctx = new SerializationContext(Recorder.serializerConfig(), path);
        JsonNode node = Recorder.serializer().serialize(value, declaredType, ctx);
        warnings.addAll(ctx.warnings());
        return node;
    }

    /** The container request of the servlet span open on this thread, or null. */
    public static Object servletRequest() {
        try {
            Object capture = SpanStack.current().servletCapture();
            return capture instanceof HttpCapture ? ((HttpCapture) capture).originalRequest : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Opens a servlet span (a root, or a child for a nested dispatch) with the request's principal and session. False when nothing records. */
    public static boolean openServlet(HttpCapture capture, String principal, String httpSessionId) {
        Recording recording = active();
        if (recording == null) {
            return false;
        }
        try {
            SpanStack stack = SpanStack.current();
            boolean root = stack.isEmpty();
            stack.push(recording, Span.KIND_HTTP, null, null, null, System.nanoTime(), capture, Recorder.propagation());
            if (root) {
                stack.trace().principal(principal);
                stack.trace().httpSessionId(httpSessionId);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Completes the servlet span opened by {@link #openServlet}; a session created or a login during the request shows up now. */
    public static void closeServlet(HttpCapture capture, HttpInfo info, String principal, String httpSessionId, Throwable thrown) {
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || open.http != capture) {
                stack.reset();
                return;
            }
            Span span = open.toSpan(System.nanoTime() - open.enteredAtNanos);
            span.http = info;
            if (thrown != null) {
                List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
                span.exception = exception(thrown, warnings);
                if (!warnings.isEmpty()) {
                    span.serializationWarnings = warnings;
                }
            }
            stack.trace().principal(principal);
            stack.trace().httpSessionId(httpSessionId);
            complete(span);
        } catch (Throwable t) {
            abandon("servlet exit handling", t);
        }
    }
}
