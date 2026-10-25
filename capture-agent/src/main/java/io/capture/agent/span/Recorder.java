package io.capture.agent.span;

import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import io.capture.agent.hook.CaptureHook;
import io.capture.agent.meta.WlsMetadata;
import io.capture.agent.serialize.GraphSerializer;
import io.capture.agent.serialize.JacksonGraphSerializer;
import io.capture.agent.serialize.SerializerConfig;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The recording lifecycle: the active recording, the hook installed while it runs, the serializer,
 * the trace writer and its counters. Start instruments the server and writes this server's
 * manifest; stop drains the writer, completes the manifest and restores the bytecode.
 */
public final class Recorder {

    private static final AtomicReference<Recording> ACTIVE = new AtomicReference<Recording>();
    private static volatile AgentConfig config;
    private static volatile SerializerConfig serializerConfig = new SerializerConfig();
    private static volatile GraphSerializer serializer = new JacksonGraphSerializer(serializerConfig);
    private static volatile TraceWriter writer;
    private static volatile WorkContextPropagation propagation = WorkContextPropagation.create();
    private static volatile CaptureHook hook;
    private static volatile String inertReason;

    private Recorder() {
    }

    /** The hook to install while recording, and the propagation that links traces across this server's threads. */
    public static void hook(CaptureHook captureHook, WorkContextPropagation tracePropagation) {
        hook = captureHook;
        propagation = tracePropagation;
    }

    /** Marks the agent as inert: no recording can start, and {@link #state()} says why. */
    public static void inert(String reason) {
        inertReason = reason;
    }

    /** "ready", "recording <id>" or "inert: <reason>". */
    public static String state() {
        if (inertReason != null) {
            return "inert: " + inertReason;
        }
        Recording recording = ACTIVE.get();
        return recording == null ? "ready" : "recording " + recording.id;
    }

    /** The serializer runs with the documented ceilings of {@link SerializerConfig}; only the output directory is configurable. */
    public static void configure(AgentConfig agentConfig) {
        config = agentConfig;
        serializerConfig = new SerializerConfig();
        serializer = new JacksonGraphSerializer(serializerConfig);
        writer = new TraceWriter(agentConfig.outDir, TraceWriter.DEFAULT_CAPACITY);
    }

    public static AgentConfig config() {
        return config;
    }

    public static GraphSerializer serializer() {
        return serializer;
    }

    static SerializerConfig serializerConfig() {
        return serializerConfig;
    }

    static TraceWriter writer() {
        return writer;
    }

    static WorkContextPropagation propagation() {
        return propagation;
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

    /**
     * Instruments the classes, writes this server's manifest, then opens the recording window
     * under the caller's id. A recording already active is replaced and logged. Throws when the
     * agent is inert.
     */
    public static synchronized Recording start(String recordingId, String name) {
        if (inertReason != null) {
            throw new IllegalStateException("agent is inert: " + inertReason);
        }
        Recording recording = new Recording(recordingId, name);
        WlsMetadata.reset();
        WlsMetadata.deployments();
        File dir = writer == null ? null : writer.recordingDir(recording);
        try {
            if (hook != null) {
                hook.install();
            }
        } catch (Throwable t) {
            AgentLog.error("hook installation failed; recording " + recording.id + " will see no calls", t);
        }
        if (dir != null) {
            ManifestWriter.write(dir, recording, null, null, null);
        }
        Recording previous = ACTIVE.getAndSet(recording);
        if (previous != null) {
            AgentLog.warn("recording " + previous.id + " replaced by " + recording.id);
        }
        AgentLog.info("RECORDING START id=" + recording.id + " name=" + recording.name + " dir=" + dir);
        return recording;
    }

    /** Closes the window, waits (bounded) for queued traces, completes the manifest, restores the bytecode. */
    public static synchronized Recording stop() {
        Recording recording = ACTIVE.getAndSet(null);
        if (recording != null) {
            TraceWriter w = writer;
            boolean flushed = w == null || w.flush(5000);
            if (w != null) {
                ManifestWriter.write(w.recordingDir(recording), recording, System.currentTimeMillis(),
                        recording.traceCount(), droppedTraces());
            }
            AgentLog.info("RECORDING STOP id=" + recording.id + " name=" + recording.name
                    + " traces=" + recording.traceCount() + " dropped=" + droppedTraces()
                    + (flushed ? "" : " (writer still busy after 5s)"));
            try {
                if (hook != null) {
                    hook.uninstall();
                }
            } catch (Throwable t) {
                AgentLog.error("hook removal failed; advice stays in place until the next stop", t);
            }
        }
        return recording;
    }

    /** Waits for the writer queue to drain, or until the timeout passes. */
    public static boolean flush(long timeoutMillis) {
        TraceWriter w = writer;
        return w == null || w.flush(timeoutMillis);
    }
}
