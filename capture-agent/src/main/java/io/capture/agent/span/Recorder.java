package io.capture.agent.span;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import io.capture.agent.serialize.GraphSerializer;
import io.capture.agent.serialize.JacksonGraphSerializer;
import io.capture.agent.serialize.SerializationContext;
import io.capture.agent.serialize.SerializerConfig;
import io.capture.agent.serialize.TypeAllowList;
import io.capture.trace.EjbInfo;
import io.capture.trace.ExceptionInfo;
import io.capture.trace.SerializationWarning;
import io.capture.trace.Span;
import io.capture.trace.ValueCapture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static entry points called from inlined advice. Must be reachable from every application
 * class loader (the agent jar is on the bootstrap path) and must never throw.
 *
 * Phase 2: on method exit, serializes arguments and result (or exception) into a
 * {@link Span} and writes it, on the calling thread. Phase 3 adds the span tree and a
 * background writer.
 */
public final class Recorder {

    private static final AtomicReference<Recording> ACTIVE = new AtomicReference<Recording>();
    private static volatile AgentConfig config;
    private static volatile SerializerConfig serializerConfig = new SerializerConfig();
    private static volatile GraphSerializer serializer = new JacksonGraphSerializer(serializerConfig);
    private static volatile SpanWriter writer;

    private Recorder() {
    }

    public static void configure(AgentConfig agentConfig) {
        config = agentConfig;
        serializerConfig = new SerializerConfig()
                .withMaxDepth(agentConfig.maxDepth)
                .withMaxElements(agentConfig.maxElements)
                .withTypeAllowList(new TypeAllowList(agentConfig.types));
        serializer = new JacksonGraphSerializer(serializerConfig);
        writer = new SpanWriter(agentConfig.outDir);
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

    public static Recording start(String name) {
        Recording recording = new Recording(name);
        Recording previous = ACTIVE.getAndSet(recording);
        if (previous != null) {
            AgentLog.warn("recording " + previous.id + " replaced by " + recording.id);
        }
        File dir = writer == null ? null : writer.recordingDir(recording);
        if (dir != null) {
            dir.mkdirs();
        }
        AgentLog.info("RECORDING START id=" + recording.id + " name=" + recording.name + " dir=" + dir);
        return recording;
    }

    public static Recording stop() {
        Recording recording = ACTIVE.getAndSet(null);
        if (recording != null) {
            AgentLog.info("RECORDING STOP id=" + recording.id + " name=" + recording.name
                    + " traces=" + recording.traceCount());
        }
        return recording;
    }

    /**
     * Called on method entry. Returns a token for {@link #exit}: 0 when nothing is recorded,
     * otherwise the entry time in nanoseconds (never 0).
     */
    public static long enter(String beanClass, String method, String signature) {
        if (ACTIVE.get() == null) {
            return 0L;
        }
        try {
            AgentLog.event("ENTER " + beanClass + "." + method + signature
                    + " thread=\"" + Thread.currentThread().getName() + "\"");
            long now = System.nanoTime();
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
            long durationNs = System.nanoTime() - token;
            Recording recording = ACTIVE.get();
            if (recording == null) {
                return;
            }
            Span span = buildSpan(self, beanClass, method, descriptor, args, result, thrown, durationNs);
            File file = writer == null ? null : writer.write(recording, span, System.currentTimeMillis() - durationNs / 1000000L);

            StringBuilder line = new StringBuilder(128)
                    .append("EXIT  ").append(beanClass).append('.').append(method)
                    .append(' ').append(durationNs / 1000 / 1000.0).append("ms");
            if (thrown != null) {
                line.append(" threw=").append(thrown.getClass().getName());
            }
            if (span.serializationWarnings != null) {
                line.append(" warnings=").append(span.serializationWarnings.size());
            }
            if (file != null) {
                line.append(" -> ").append(file.getName());
            }
            AgentLog.event(line.toString());
        } catch (Throwable t) {
            try {
                AgentLog.error("exit handling failed for " + beanClass + "." + method, t);
            } catch (Throwable ignored) {
                // never into the business call
            }
        }
    }

    private static Span buildSpan(Object self, String beanClass, String method, String descriptor,
                                  Object[] args, Object result, Throwable thrown, long durationNs) {
        Class<?> bean = MethodSignatures.beanClass(self, beanClass);
        MethodSignatures.MethodInfo info = MethodSignatures.resolve(bean, method, descriptor);

        Span span = new Span();
        span.id = "s1";
        span.depth = 0;
        span.order = 0;
        span.kind = Span.KIND_EJB;
        span.thread = Thread.currentThread().getName();
        span.startNs = 0;
        span.durationNs = durationNs;
        span.ejb = new EjbInfo();
        span.ejb.beanClass = beanClass;
        span.ejb.method = method;
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
        return span;
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
