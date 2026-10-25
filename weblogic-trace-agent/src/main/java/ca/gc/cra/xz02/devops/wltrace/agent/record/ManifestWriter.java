package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentVersion;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Writes this server's manifest into the recording directory at start and again, completed, at
 * stop: {@code recording-<serverName>.json}, so the servers of a domain sharing one recording do
 * not overwrite each other ({@code recording.json} without a server name). Never throws.
 */
public final class ManifestWriter {

    private final ServerRuntime server;
    private final AgentLog log;

    public ManifestWriter(ServerRuntime server, AgentLog log) {
        this.server = server;
        this.log = log;
    }

    /** The manifest with start time and agent facts, before any trace is written. */
    public void writeStarted(File directory, Recording recording) {
        write(directory, recording, null, null, null);
    }

    /** The manifest with end time and counts. */
    public void writeCompleted(File directory, Recording recording, long endedAtMillis, long traceCount, long droppedTraces) {
        write(directory, recording, endedAtMillis, traceCount, droppedTraces);
    }

    String fileName() {
        String serverName = server.serverName();
        return serverName == null || serverName.isEmpty()
                ? "recording.json"
                : "recording-" + serverName.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
    }

    private void write(File directory, Recording recording, Long endedAtMillis, Long traceCount, Long droppedTraces) {
        try {
            RecordingManifest manifest = new RecordingManifest();
            manifest.id = recording.id;
            manifest.name = recording.name;
            manifest.startedAt = OpenTrace.isoInstant(recording.startedAtMillis);
            manifest.endedAt = endedAtMillis == null ? null : OpenTrace.isoInstant(endedAtMillis);
            manifest.serverName = server.serverName();
            manifest.agent = new RecordingManifest.Agent();
            manifest.agent.version = AgentVersion.VERSION;
            manifest.agent.build = AgentVersion.BUILD;
            manifest.agent.jvm = System.getProperty("java.version");
            manifest.traceCount = traceCount;
            manifest.droppedTraces = droppedTraces;

            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("cannot create " + directory);
            }
            File target = new File(directory, fileName());
            File temporary = new File(directory, fileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary.toPath())) {
                TraceJson.write(manifest, out);
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable failure) {
            log.error("could not write " + fileName() + " for " + recording.id, failure);
        }
    }
}
