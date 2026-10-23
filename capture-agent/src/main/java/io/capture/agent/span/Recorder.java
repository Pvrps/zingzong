package io.capture.agent.span;

import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Static entry points called from inlined advice. Must be reachable from every application
 * class loader (the agent jar is on the bootstrap path) and must never throw.
 *
 * Phase 1: logs one line per EJB method entry and exit while a recording is active.
 */
public final class Recorder {

    private static final AtomicReference<Recording> ACTIVE = new AtomicReference<Recording>();
    private static volatile AgentConfig config;

    private Recorder() {
    }

    public static void configure(AgentConfig agentConfig) {
        config = agentConfig;
    }

    public static AgentConfig config() {
        return config;
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
        AgentLog.info("RECORDING START id=" + recording.id + " name=" + recording.name);
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
     * Called on method entry. Returns a token for {@link #exit}: 0 when nothing was recorded.
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
    public static void exit(long token, String beanClass, String method, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            long durationNs = System.nanoTime() - token;
            StringBuilder line = new StringBuilder(96)
                    .append("EXIT  ").append(beanClass).append('.').append(method)
                    .append(' ').append(durationNs / 1000 / 1000.0).append("ms");
            if (thrown != null) {
                line.append(" threw=").append(thrown.getClass().getName());
            }
            AgentLog.event(line.toString());
        } catch (Throwable ignored) {
            // never into the business call
        }
    }
}
