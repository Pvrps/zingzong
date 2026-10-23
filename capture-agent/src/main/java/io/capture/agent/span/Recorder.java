package io.capture.agent.span;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import io.capture.agent.hook.InstrumentationHook;
import io.capture.agent.serialize.GraphSerializer;
import io.capture.agent.serialize.JacksonGraphSerializer;
import io.capture.agent.serialize.SerializationContext;
import io.capture.agent.serialize.SerializerConfig;
import io.capture.agent.serialize.TypeAllowList;
import io.capture.trace.ExceptionInfo;
import io.capture.trace.SerializationWarning;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.ValueCapture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static entry points called from inlined advice. Must be reachable from every application
 * class loader (the agent jar is on the bootstrap path) and must never throw.
 *
 * Enter pushes a span on the thread's {@link SpanStack}; exit serializes arguments and
 * result (or exception) on the calling thread, pops, and when the stack empties hands the
 * finished {@link Trace} to the {@link TraceWriter}'s queue.
 */
public final class Recorder {

    private static final AtomicReference<Recording> ACTIVE = new AtomicReference<Recording>();
    private static volatile AgentConfig config;
    private static volatile SerializerConfig serializerConfig = new SerializerConfig();
    private static volatile GraphSerializer serializer = new JacksonGraphSerializer(serializerConfig);
    private static volatile TraceWriter writer;
    private static volatile TracePropagation propagation = TracePropagation.NONE;
    private static volatile InstrumentationHook hook = InstrumentationHook.NONE;
    private static volatile String hookName = "none";

    private Recorder() {
    }

    /** The hook to install while recording. */
    public static void hook(InstrumentationHook instrumentationHook, String name) {
        hook = instrumentationHook;
        hookName = name;
    }

    public static void configure(AgentConfig agentConfig) {
        config = agentConfig;
        serializerConfig = new SerializerConfig()
                .withMaxDepth(agentConfig.maxDepth)
                .withMaxElements(agentConfig.maxElements)
                .withTypeAllowList(new TypeAllowList(agentConfig.types));
        serializer = new JacksonGraphSerializer(serializerConfig);
        writer = new TraceWriter(agentConfig.outDir, TraceWriter.DEFAULT_CAPACITY);
    }

    public static AgentConfig config() {
        return config;
    }

    public static GraphSerializer serializer() {
        return serializer;
    }

    public static boolean isEnabled() {
        return ACTIVE.get() != null;
    }

    public static Recording active() {
        return ACTIVE.get();
    }

    public static long droppedTraces() {
        TraceWriter w = writer;
        return w == null ? 0L : w.dropped();
    }

    public static long writtenTraces() {
        TraceWriter w = writer;
        return w == null ? 0L : w.written();
    }

    public static int queuedTraces() {
        TraceWriter w = writer;
        return w == null ? 0 : w.queued();
    }

    public static Recording start(String name) {
        return start(name, null, null);
    }

    /**
     * Instruments the bean classes, writes recording.json, then opens the recording window.
     * Filters are optional and off when null: on a shared server they keep other traffic out.
     */
    public static synchronized Recording start(String name, String principalFilter, String httpSessionFilter) {
        Recording recording = new Recording(name, principalFilter, httpSessionFilter);
        File dir = writer == null ? null : writer.recordingDir(recording);
        if (dir != null) {
            ManifestWriter.write(dir, recording, hookName, null, null, null);
        }
        try {
            hook.install();
        } catch (Throwable t) {
            AgentLog.error("hook installation failed; recording " + recording.id + " will see no calls", t);
        }
        Recording previous = ACTIVE.getAndSet(recording);
        if (previous != null) {
            AgentLog.warn("recording " + previous.id + " replaced by " + recording.id);
        }
        AgentLog.info("RECORDING START id=" + recording.id + " name=" + recording.name + " dir=" + dir
                + (recording.hasFilters() ? " filters: principal=" + recording.principalFilter
                        + " httpSessionId=" + recording.httpSessionFilter : ""));
        return recording;
    }

    /** Closes the window, waits (bounded) for queued traces, completes recording.json, restores the bytecode. */
    public static synchronized Recording stop() {
        Recording recording = ACTIVE.getAndSet(null);
        if (recording != null) {
            TraceWriter w = writer;
            boolean flushed = w == null || w.flush(5000);
            if (w != null) {
                ManifestWriter.write(w.recordingDir(recording), recording, hookName, System.currentTimeMillis(),
                        recording.traceCount(), droppedTraces());
            }
            AgentLog.info("RECORDING STOP id=" + recording.id + " name=" + recording.name
                    + " traces=" + recording.traceCount() + " dropped=" + droppedTraces()
                    + (flushed ? "" : " (writer still busy after 5s)"));
            try {
                hook.uninstall();
            } catch (Throwable t) {
                AgentLog.error("hook removal failed; advice stays in place until the next stop", t);
            }
        }
        return recording;
    }

    /** Waits for the writer queue to drain; for tests and the stop path. */
    public static boolean flush(long timeoutMillis) {
        TraceWriter w = writer;
        return w == null || w.flush(timeoutMillis);
    }

    /**
     * Called on method entry. Returns 0 when nothing is recorded, otherwise a non-zero token
     * that {@link #exit} uses to find its span (the top of the thread's stack).
     */
    public static long enter(String beanClass, String method, String signature) {
        Recording recording = ACTIVE.get();
        if (recording == null) {
            return 0L;
        }
        try {
            SpanStack stack = SpanStack.current();
            boolean root = stack.isEmpty();
            String principal = null;
            String httpSessionId = null;
            if (root) {
                principal = WlsRuntime.principal();
                httpSessionId = WlsRuntime.httpSessionId();
                if (!recording.accepts(principal, httpSessionId)) {
                    return 0L;
                }
            }
            long now = System.nanoTime();
            String transaction = WlsRuntime.transactionStatus();
            stack.push(recording, beanClass, method, now, transaction, propagation);
            if (root) {
                stack.trace().principal(principal);
                stack.trace().httpSessionId(httpSessionId);
            }
            return now == 0L ? 1L : now;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Called on method exit, normal or exceptional. Token 0 means {@link #enter} recorded nothing. */
    public static void exit(long token, Object self, String beanClass, String method, String descriptor,
                            Object[] args, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            SpanStack stack = SpanStack.current();
            OpenSpan open = stack.top();
            if (open == null || !open.beanClass.equals(beanClass) || !open.method.equals(method)) {
                // Enter/exit mismatch (recording boundary crossed mid-trace): abandon this thread's state.
                stack.reset();
                return;
            }
            long durationNs = System.nanoTime() - open.enteredAtNanos;
            Span span = open.toSpan(durationNs);
            fill(span, self, beanClass, method, descriptor, args, result, thrown);

            Recording recording = stack.trace().recording;
            Trace finished = stack.pop(span, propagation);
            if (finished != null) {
                TraceWriter w = writer;
                if (w != null) {
                    w.offer(recording, finished);
                }
            }
        } catch (Throwable t) {
            try {
                SpanStack.current().reset();
                AgentLog.error("exit handling failed for " + beanClass + "." + method, t);
            } catch (Throwable ignored) {
                // never into the business call
            }
        }
    }

    private static void fill(Span span, Object self, String beanClass, String method, String descriptor,
                             Object[] args, Object result, Throwable thrown) {
        Class<?> bean = MethodSignatures.beanClass(self, beanClass);
        MethodSignatures.MethodInfo info = MethodSignatures.resolve(bean, method, descriptor);
        span.ejb.signature = info.signature;

        List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
        span.args = new ArrayList<ValueCapture>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                span.args.add(capture(args[i], info.parameterType(i), "$.args[" + i + "]", warnings));
            }
        }
        if (thrown != null) {
            span.exception = new ExceptionInfo();
            span.exception.className = thrown.getClass().getName();
            span.exception.message = thrown.getMessage();
            span.exception.value = serialize(thrown, Throwable.class, "$.exception", warnings);
        } else if (!info.returnsVoid()) {
            span.result = capture(result, info.returnType == null ? Object.class : info.returnType, "$.result", warnings);
        }
        if (!warnings.isEmpty()) {
            span.serializationWarnings = warnings;
        }
    }

    private static ValueCapture capture(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        ValueCapture capture = new ValueCapture();
        capture.declaredType = declaredType.getName();
        capture.value = serialize(value, declaredType, path, warnings);
        return capture;
    }

    private static JsonNode serialize(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        SerializationContext ctx = new SerializationContext(serializerConfig, path);
        JsonNode node = serializer.serialize(value, declaredType, ctx);
        warnings.addAll(ctx.warnings());
        return node;
    }
}
