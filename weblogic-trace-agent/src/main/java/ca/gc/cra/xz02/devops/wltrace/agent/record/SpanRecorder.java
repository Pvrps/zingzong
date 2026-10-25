package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.SerializationContext;
import ca.gc.cra.xz02.devops.wltrace.model.ExceptionInfo;
import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.ValueCapture;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The shape every kind of span shares, as template methods. {@link #enter} checks that a
 * recording is active, asks the subclass to {@link #readFacts read the call's facts}, and opens
 * a span on the thread's stack; {@link #exit} finds that span by its token, describes it from the
 * facts, {@link #recordOutcome records the outcome}, and pops it, handing the finished trace to
 * the writer when the span was the root. Neither throws into the instrumented call: a failure
 * records nothing and, on exit, abandons the thread's stack so the next root starts clean.
 *
 * @param <F> the facts a subclass reads at enter and uses at exit
 */
public abstract class SpanRecorder<F extends SpanFacts> {

    /** The token {@link #enter} returns when nothing is recorded; {@link #exit} does nothing for it. */
    public static final long NOT_RECORDED = 0L;

    private final RecorderContext context;
    protected final AgentLog log;

    protected SpanRecorder(RecorderContext context) {
        this.context = context;
        this.log = context.log;
    }

    /** {@code EJB}, {@code HTTP} or {@code SQL}: the span kind this recorder produces. */
    public abstract String kind();

    /**
     * Reads this kind's facts from the call as the advice saw it; the three arguments are what the
     * advice passes, in its order, null where it passes fewer. Returns null when the call is not
     * one this kind recognises, in which case nothing is recorded.
     */
    protected abstract F readFacts(Object first, Object second, Object third);

    /**
     * Opens a span for a call. Returns {@link #NOT_RECORDED} when no recording is active, the facts
     * cannot be read or anything fails; otherwise the token the matching {@link #exit} needs.
     */
    public final long enter(Object first, Object second, Object third) {
        Recording recording = context.active.get();
        if (recording == null) {
            return NOT_RECORDED;
        }
        try {
            F facts = readFacts(first, second, third);
            return facts == null ? NOT_RECORDED : open(recording, facts);
        } catch (Throwable failure) {
            return NOT_RECORDED;
        }
    }

    /**
     * Closes the span opened with the token: describes it from its facts, records the outcome,
     * completes the trace when it was the root. A token that does not match the top of the stack
     * means the recording started or stopped mid-call; the thread's stack is abandoned.
     */
    public final void exit(long token, Object[] arguments, Object result, Throwable thrown) {
        if (token == NOT_RECORDED) {
            return;
        }
        try {
            SpanStack stack = context.stacks.current();
            OpenSpan open = stack.top();
            if (open == null || open.token != token) {
                stack.reset();
                return;
            }
            @SuppressWarnings("unchecked")
            F facts = (F) open.facts;
            Span span = open.toSpan(System.nanoTime());
            facts.describe(span);
            List<SerializationWarning> warnings = new ArrayList<>();
            recordOutcome(span, facts, arguments, result, thrown, warnings);
            if (!warnings.isEmpty()) {
                span.serializationWarnings = warnings;
            }
            OpenTrace trace = stack.trace();
            if (trace.principal() == null) {
                trace.principal(facts.principal(context.server));
            }
            if (trace.httpSessionId() == null) {
                trace.httpSessionId(facts.httpSessionId(context.server));
            }
            Optional<Trace> finished = stack.pop(span, context.server.serverName(), context.runtimeMBeans.deployments(), context.propagation);
            if (finished.isPresent()) {
                context.writer.offer(trace.recording, finished.get());
            }
        } catch (Throwable failure) {
            abandon(failure);
        }
    }

    /**
     * Writes what the call produced onto the span. The default serializes the arguments under the
     * facts' signature, then the exception or, for a non-void method, the result. A subclass whose
     * outcome is not arguments and a result overrides this.
     */
    protected void recordOutcome(Span span, F facts, Object[] arguments, Object result, Throwable thrown, List<SerializationWarning> warnings) {
        MethodSignature signature = facts.signature();
        span.args = new ArrayList<>();
        if (arguments != null) {
            for (int index = 0; index < arguments.length; index++) {
                span.args.add(capture(arguments[index], signature.parameterType(index), "$.args[" + index + "]", warnings));
            }
        }
        if (thrown != null) {
            span.exception = exception(thrown, warnings);
        } else if (!signature.returnsVoid()) {
            span.result = capture(result, signature.returnType(), "$.result", warnings);
        }
    }

    private long open(Recording recording, F facts) {
        SpanStack stack = context.stacks.current();
        boolean root = stack.isEmpty();
        // Read before the push, so a failing lookup cannot leave a half-opened span behind.
        String principal = root ? facts.principal(context.server) : null;
        String httpSessionId = root ? facts.httpSessionId(context.server) : null;
        OpenSpan span = stack.push(recording, this, facts, System.nanoTime(), context.propagation);
        if (root) {
            stack.trace().principal(principal);
            stack.trace().httpSessionId(httpSessionId);
        }
        return span.token;
    }

    /** Abandons the thread's stack after an exit failed, so the next root starts clean; logs the failure. */
    private void abandon(Throwable failure) {
        try {
            context.stacks.current().reset();
            log.error(kind() + " span exit failed", failure);
        } catch (Throwable ignored) {
            // never into the business call
        }
    }

    /** The facts of the nearest open span of a type on this thread, innermost first. */
    protected final <T extends SpanFacts> Optional<T> nearestOpen(Class<T> factsType) {
        return context.stacks.current().nearestFacts(factsType);
    }

    protected final boolean recordingActive() {
        return context.active.get() != null;
    }

    /** A value serialized under its declared type, with the warnings the walk produced appended. */
    protected final ValueCapture capture(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        ValueCapture captured = new ValueCapture();
        captured.declaredType = declaredType.getName();
        captured.value = serialize(value, declaredType, path, warnings);
        return captured;
    }

    /** The exception a call threw: class, message and the serialized object. */
    protected final ExceptionInfo exception(Throwable thrown, List<SerializationWarning> warnings) {
        ExceptionInfo exception = new ExceptionInfo();
        exception.className = thrown.getClass().getName();
        exception.message = thrown.getMessage();
        exception.value = serialize(thrown, Throwable.class, "$.exception", warnings);
        return exception;
    }

    protected final JsonNode serialize(Object value, Class<?> declaredType, String path, List<SerializationWarning> warnings) {
        SerializationContext serialization = new SerializationContext(context.ceilings, path);
        JsonNode node = context.serializer.serialize(value, declaredType, serialization);
        warnings.addAll(serialization.warnings());
        return node;
    }
}
