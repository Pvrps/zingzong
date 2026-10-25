package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.SpanRecorder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One instrumentation point of the agent: the WebLogic classes it advises, the advice bound to
 * their methods, and the recorder that advice reports to through {@code TraceRuntime}. A hook
 * with named target classes needs them on the system class path; one that is not
 * {@link #optional()} makes the whole agent inert when they are missing.
 */
public abstract class Hook {

    private final String name;
    private final SpanRecorder<?> recorder;

    protected Hook(String name, SpanRecorder<?> recorder) {
        this.name = name;
        this.recorder = recorder;
    }

    public final String name() {
        return name;
    }

    public final SpanRecorder<?> recorder() {
        return recorder;
    }

    /** The classes advised by name; empty when the targets are matched by annotation and loader. */
    public abstract List<String> targetClassNames();

    /** The advice to inline, each binding with its own type and method matchers. */
    public abstract List<AdviceBinding> bindings();

    /** Whether the agent may record without this hook when its target classes are absent. */
    public boolean optional() {
        return false;
    }

    /** True when the class is one of this hook's named targets. */
    public final boolean targets(String className) {
        return targetClassNames().contains(className);
    }

    /** The named targets the system class path cannot resolve. */
    public final List<String> missingTargetClasses() {
        return targetClassNames().stream()
                .filter(className -> ClassLoader.getSystemResource(className.replace('.', '/') + ".class") == null)
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return name;
    }
}
