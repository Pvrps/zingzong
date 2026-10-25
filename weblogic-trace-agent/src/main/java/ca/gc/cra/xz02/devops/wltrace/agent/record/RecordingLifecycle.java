package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.HookInstaller;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.RuntimeMBeans;

import java.io.File;
import java.util.Optional;

/**
 * Starts and stops recordings. Start installs the hooks, writes this server's manifest and opens
 * the window under the caller's id; stop closes the window, drains the writer, completes the
 * manifest and removes the hooks, restoring the original bytecode. An inert agent refuses to start
 * and says why.
 */
public final class RecordingLifecycle {

    private static final long FLUSH_TIMEOUT_MILLIS = 5000;

    private final ActiveRecording active;
    private final HookInstaller hooks;
    private final TraceWriter writer;
    private final ManifestWriter manifests;
    private final RuntimeMBeans runtimeMBeans;
    private final Optional<String> inertReason;
    private final AgentLog log;

    public RecordingLifecycle(ActiveRecording active, HookInstaller hooks, TraceWriter writer, ManifestWriter manifests,
                              RuntimeMBeans runtimeMBeans, Optional<String> inertReason, AgentLog log) {
        this.active = active;
        this.hooks = hooks;
        this.writer = writer;
        this.manifests = manifests;
        this.runtimeMBeans = runtimeMBeans;
        this.inertReason = inertReason;
        this.log = log;
    }

    /** Why no recording can start in this JVM, or empty when it can. */
    public Optional<String> inertReason() {
        return inertReason;
    }

    /** {@code ready}, {@code recording <id>} or {@code inert: <reason>}. */
    public String state() {
        if (inertReason.isPresent()) {
            return "inert: " + inertReason.get();
        }
        Recording recording = active.get();
        return recording == null ? "ready" : "recording " + recording.id;
    }

    public Optional<Recording> active() {
        return Optional.ofNullable(active.get());
    }

    public boolean hooksInstalled() {
        return hooks.isInstalled();
    }

    /**
     * Instruments the server, writes this server's manifest, then opens the recording window under
     * the caller's id. A recording already active is replaced and logged.
     *
     * @throws IllegalStateException when the agent is inert
     * @throws IllegalArgumentException when the id is not a plain file name
     */
    public synchronized Recording start(String recordingId, String name) {
        if (inertReason.isPresent()) {
            throw new IllegalStateException("agent is inert: " + inertReason.get());
        }
        Recording recording = new Recording(recordingId, name);
        runtimeMBeans.reset();
        runtimeMBeans.deployments();
        File directory = writer.recordingDirectory(recording);
        try {
            hooks.install();
        } catch (Throwable failure) {
            log.error("hook installation failed; recording " + recording.id + " will see no calls", failure);
        }
        manifests.writeStarted(directory, recording);
        Recording previous = active.replace(recording);
        if (previous != null) {
            log.warn("recording " + previous.id + " replaced by " + recording.id);
        }
        log.info("RECORDING START id=" + recording.id + " name=" + recording.name + " dir=" + directory);
        return recording;
    }

    /** Closes the window, waits (bounded) for queued traces, completes the manifest, restores the bytecode; empty when none was active. */
    public synchronized Optional<Recording> stop() {
        Recording recording = active.replace(null);
        if (recording == null) {
            return Optional.empty();
        }
        boolean flushed = writer.flush(FLUSH_TIMEOUT_MILLIS);
        manifests.writeCompleted(writer.recordingDirectory(recording), recording, System.currentTimeMillis(),
                recording.traceCount(), writer.dropped());
        log.info("RECORDING STOP id=" + recording.id + " name=" + recording.name
                + " traces=" + recording.traceCount() + " dropped=" + writer.dropped()
                + (flushed ? "" : " (writer still busy after " + FLUSH_TIMEOUT_MILLIS + "ms)"));
        try {
            hooks.uninstall();
        } catch (Throwable failure) {
            log.error("hook removal failed; advice stays in place until the next stop", failure);
        }
        return Optional.of(recording);
    }
}
