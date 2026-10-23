package io.capture.agent.http;

import java.io.ByteArrayOutputStream;

/**
 * Collects a body as it streams past. Everything is kept up to the safety ceiling; beyond
 * it, bytes are counted but not stored, so the trace can say how much was dropped.
 */
public final class BodyBuffer {

    /** 64 MiB: a body past this is a file transfer, not a request payload; a marker says how much more there was. */
    public static final long DEFAULT_CEILING = 64L * 1024 * 1024;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final long ceiling;
    private long length;
    private boolean touched;

    public BodyBuffer(long ceiling) {
        this.ceiling = ceiling;
    }

    public synchronized void write(int b) {
        touched = true;
        if (length < ceiling) {
            bytes.write(b);
        }
        length++;
    }

    public synchronized void write(byte[] b, int off, int len) {
        if (len <= 0) {
            return;
        }
        touched = true;
        long room = ceiling - length;
        if (room > 0) {
            bytes.write(b, off, (int) Math.min(len, room));
        }
        length += len;
    }

    /** Whether any byte went through, even zero-length reads count as "the stream was used". */
    public synchronized void touch() {
        touched = true;
    }

    public synchronized boolean touched() {
        return touched;
    }

    public synchronized long length() {
        return length;
    }

    public synchronized long dropped() {
        return Math.max(0, length - ceiling);
    }

    public synchronized byte[] bytes() {
        return bytes.toByteArray();
    }
}
