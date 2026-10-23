package io.capture.agent.span;

import io.capture.agent.AgentLog;
import io.capture.trace.RecordingRef;
import io.capture.trace.Span;
import io.capture.trace.Trace;
import io.capture.trace.TraceInfo;
import io.capture.trace.TraceJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Writes one trace file per completed span under {@code <out>/<recordingId>/}, atomically
 * ({@code .tmp} then rename). Phase 2: synchronous, on the calling thread. Phase 3 moves
 * this behind a bounded queue and groups spans into trees.
 */
final class SpanWriter {

    private final File outDir;

    SpanWriter(File outDir) {
        this.outDir = outDir;
    }

    File recordingDir(Recording recording) {
        return new File(outDir, recording.id);
    }

    /** Never throws; failures go to agent.log and the span is dropped. */
    File write(Recording recording, Span span, long startedAtMillis) {
        try {
            File dir = recordingDir(recording);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("cannot create " + dir);
            }
            long seq = recording.nextSeq();
            Trace trace = new Trace();
            trace.recording = new RecordingRef();
            trace.recording.id = recording.id;
            trace.recording.name = recording.name;
            trace.recording.seq = seq;
            trace.trace = new TraceInfo();
            trace.trace.id = "t-" + UUID.randomUUID().toString().substring(0, 8);
            trace.trace.startedAt = iso(startedAtMillis);
            trace.trace.durationMs = span.durationNs / 1000000L;
            trace.spans.add(span);

            File target = new File(dir, String.format("%06d-%s.json", seq, trace.trace.id));
            File tmp = new File(dir, target.getName() + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                TraceJson.write(trace, out);
            } finally {
                out.close();
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Throwable t) {
            AgentLog.error("could not write span " + describe(span), t);
            return null;
        }
    }

    private static String describe(Span span) {
        return span == null || span.ejb == null ? "?" : span.ejb.beanClass + "." + span.ejb.method;
    }

    private static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
