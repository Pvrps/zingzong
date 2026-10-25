package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded queue drained by one daemon thread that writes {@code <out>/<recordingId>/<seq>-<traceId>.json}
 * atomically ({@code .tmp}, then rename). A full queue drops the trace and counts it; the request
 * thread never blocks and never throws.
 */
public final class TraceWriter {

    public static final int DEFAULT_CAPACITY = 1000;

    private static final class Pending {
        final Recording recording;
        final Trace trace;

        Pending(Recording recording, Trace trace) {
            this.recording = recording;
            this.trace = trace;
        }
    }

    private final File outputDirectory;
    private final AgentLog log;
    private final BlockingQueue<Pending> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicBoolean busy = new AtomicBoolean();

    public TraceWriter(File outputDirectory, int capacity, AgentLog log) {
        this.outputDirectory = outputDirectory;
        this.log = log;
        this.queue = new ArrayBlockingQueue<>(capacity);
        Thread thread = new Thread(this::drain, "weblogic-trace-writer");
        thread.setDaemon(true);
        thread.start();
    }

    public File outputDirectory() {
        return outputDirectory;
    }

    public File recordingDirectory(Recording recording) {
        return new File(outputDirectory, recording.id);
    }

    /** Queues the trace under the recording's next sequence number; false when it was dropped. */
    public boolean offer(Recording recording, Trace trace) {
        try {
            trace.recording.seq = recording.nextSequence();
            if (queue.offer(new Pending(recording, trace))) {
                return true;
            }
            dropped.incrementAndGet();
            log.warnOnce("queue-full", "trace queue full (" + queue.size() + "); dropping traces, see DroppedTraces on the Control MBean");
        } catch (Throwable failure) {
            dropped.incrementAndGet();
        }
        return false;
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    public int queued() {
        return queue.size();
    }

    /** Waits until everything queued so far is written, or the timeout passes; true when drained. */
    public boolean flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!queue.isEmpty() || busy.get()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void drain() {
        while (true) {
            Pending pending;
            try {
                pending = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (pending == null) {
                continue;
            }
            busy.set(true);
            try {
                write(pending);
            } catch (Throwable failure) {
                log.error("could not write trace " + pending.trace.trace.id, failure);
            } finally {
                busy.set(false);
            }
        }
    }

    private void write(Pending pending) throws IOException {
        File directory = recordingDirectory(pending.recording);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
        Trace trace = pending.trace;
        File target = new File(directory, String.format("%06d-%s.json", trace.recording.seq, trace.trace.id));
        File temporary = new File(directory, target.getName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary.toPath())) {
            TraceJson.write(trace, out);
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        written.incrementAndGet();
        log.event("TRACE " + trace.trace.id + " seq=" + trace.recording.seq
                + " root=" + rootLabel(trace)
                + " spans=" + trace.spans.size() + " " + trace.trace.durationMs + "ms"
                + (trace.trace.principal == null ? "" : " principal=" + trace.trace.principal)
                + " -> " + target.getName());
    }

    private static String rootLabel(Trace trace) {
        if (trace.spans.isEmpty()) {
            return "?";
        }
        Span root = trace.spans.get(0);
        if (root.ejb != null) {
            return root.ejb.beanClass.substring(root.ejb.beanClass.lastIndexOf('.') + 1) + "." + root.ejb.method;
        }
        if (root.http != null) {
            return root.http.method + " " + root.http.path;
        }
        if (root.sql != null) {
            return root.sql.dataSource + "." + root.sql.operation;
        }
        return root.kind;
    }
}
