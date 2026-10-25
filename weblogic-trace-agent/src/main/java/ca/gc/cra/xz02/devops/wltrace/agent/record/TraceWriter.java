package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded queue drained by one daemon thread that writes {@code <out>/<recordingId>/<seq>-<traceId>.json}
 * atomically ({@code .tmp} then rename). A full queue drops the trace and counts it; the
 * request thread never blocks.
 */
final class TraceWriter {

    static final int DEFAULT_CAPACITY = 1000;

    private final File outDir;
    private final BlockingQueue<Pending> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean dropWarned = new AtomicBoolean();
    private final Thread thread;

    TraceWriter(File outDir, int capacity) {
        this.outDir = outDir;
        this.queue = new ArrayBlockingQueue<Pending>(capacity);
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                drain();
            }
        }, "weblogic-trace-writer");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    File recordingDir(Recording recording) {
        return new File(outDir, recording.id);
    }

    /** Never blocks, never throws. Returns false when the trace was dropped. */
    boolean offer(Recording recording, Trace trace) {
        try {
            trace.recording.seq = recording.nextSeq();
            if (queue.offer(new Pending(recording, trace))) {
                return true;
            }
            dropped.incrementAndGet();
            if (dropWarned.compareAndSet(false, true)) {
                AgentLog.warn("trace queue full (" + queue.size() + "); dropping traces, see DroppedTraces on the Control MBean");
            }
        } catch (Throwable t) {
            dropped.incrementAndGet();
        }
        return false;
    }

    long dropped() {
        return dropped.get();
    }

    long written() {
        return written.get();
    }

    int queued() {
        return queue.size();
    }

    /** Waits until everything queued so far has been written, or the timeout passes. */
    boolean flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!queue.isEmpty() || busy.get()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
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
            } catch (InterruptedException e) {
                return;
            }
            if (pending == null) {
                continue;
            }
            busy.set(true);
            try {
                write(pending);
            } catch (Throwable t) {
                AgentLog.error("could not write trace " + pending.trace.trace.id, t);
            } finally {
                busy.set(false);
            }
        }
    }

    private void write(Pending pending) throws Exception {
        File dir = recordingDir(pending.recording);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("cannot create " + dir);
        }
        Trace trace = pending.trace;
        File target = new File(dir, String.format("%06d-%s.json", trace.recording.seq, trace.trace.id));
        File tmp = new File(dir, target.getName() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            TraceJson.write(trace, out);
        } finally {
            out.close();
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        written.incrementAndGet();

        Span root = trace.spans.isEmpty() ? null : trace.spans.get(0);
        AgentLog.event("TRACE " + trace.trace.id + " seq=" + trace.recording.seq
                + " root=" + (root == null || root.ejb == null ? "?" : simpleName(root.ejb.beanClass) + "." + root.ejb.method)
                + " spans=" + trace.spans.size() + " " + trace.trace.durationMs + "ms"
                + (trace.trace.principal == null ? "" : " principal=" + trace.trace.principal)
                + " -> " + target.getName());
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static final class Pending {
        final Recording recording;
        final Trace trace;

        Pending(Recording recording, Trace trace) {
            this.recording = recording;
            this.trace = trace;
        }
    }
}
