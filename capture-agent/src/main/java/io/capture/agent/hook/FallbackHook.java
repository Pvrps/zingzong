package io.capture.agent.hook;

import io.capture.agent.AgentLog;

/**
 * The container hook with its startup self-check: after installing, if WebLogic's invoker
 * classes are loaded but the advice did not land on them (a different shape of
 * {@code invoke} than docs/container-hook.md describes), warn and switch to the annotation
 * hook for this and every later recording. On a server the invokers are loaded with the
 * first EJB module, so the check is meaningful from the first recording on.
 */
public final class FallbackHook implements InstrumentationHook {

    private final CaptureHook container;
    private final CaptureHook annotation;
    private volatile InstrumentationHook current;

    public FallbackHook(CaptureHook container, CaptureHook annotation) {
        this.container = container;
        this.annotation = annotation;
        this.current = container;
    }

    @Override
    public synchronized void install() {
        current.install();
        if (current == container && container.containerTargetsMissed()) {
            AgentLog.warn("container hook self-check failed: the invoker classes are loaded but were not instrumented; "
                    + "falling back to the annotation hook");
            container.uninstall();
            current = annotation;
            annotation.install();
        }
    }

    @Override
    public synchronized void uninstall() {
        current.uninstall();
    }

    @Override
    public synchronized boolean isInstalled() {
        return current.isInstalled();
    }

    @Override
    public String name() {
        return current.name();
    }
}
