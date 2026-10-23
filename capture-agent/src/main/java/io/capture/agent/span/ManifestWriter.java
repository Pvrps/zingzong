package io.capture.agent.span;

import io.capture.agent.AgentLog;
import io.capture.agent.AgentVersion;
import io.capture.trace.RecordingManifest;
import io.capture.trace.TraceJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/** Writes {@code recording.json} at start and again, completed, at stop. Never throws. */
final class ManifestWriter {

    static final String FILE_NAME = "recording.json";

    private ManifestWriter() {
    }

    static void write(File dir, Recording recording, String hook, Long endedAtMillis, Long traceCount, Long dropped) {
        try {
            RecordingManifest manifest = new RecordingManifest();
            manifest.id = recording.id;
            manifest.name = recording.name;
            manifest.startedAt = iso(recording.startedAtMillis);
            manifest.endedAt = endedAtMillis == null ? null : iso(endedAtMillis);
            manifest.serverName = WlsRuntime.serverName();
            manifest.agent = new RecordingManifest.Agent();
            manifest.agent.version = AgentVersion.VERSION;
            manifest.agent.build = AgentVersion.BUILD;
            manifest.agent.hook = hook;
            manifest.agent.jvm = System.getProperty("java.version");
            manifest.filters = recording.filters();
            manifest.traceCount = traceCount;
            manifest.droppedTraces = dropped;

            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("cannot create " + dir);
            }
            File target = new File(dir, FILE_NAME);
            File tmp = new File(dir, FILE_NAME + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                TraceJson.write(manifest, out);
            } finally {
                out.close();
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            AgentLog.error("could not write " + FILE_NAME + " for " + recording.id, t);
        }
    }

    static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
