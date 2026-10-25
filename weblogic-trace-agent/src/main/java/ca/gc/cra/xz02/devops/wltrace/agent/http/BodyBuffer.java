package ca.gc.cra.xz02.devops.wltrace.agent.http;

import java.io.ByteArrayOutputStream;

/**
 * Collects a body as it streams past. Everything is kept up to the ceiling; beyond it bytes are
 * counted but not stored, so the trace can say how much was dropped.
 */
public final class BodyBuffer {

    /** 64 MiB: a body past this is a file transfer, not a request payload; the trace says how much more there was. */
    public static final long DEFAULT_CEILING = 64L * 1024 * 1024;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final long ceiling;
    private long length;
    private boolean touched;

    public BodyBuffer(long ceiling) {
        this.ceiling = ceiling;
    }

    public synchronized void write(int value) {
        touched = true;
        if (length < ceiling) {
            bytes.write(value);
        }
        length++;
    }

    public synchronized void write(byte[] buffer, int offset, int count) {
        if (count <= 0) {
            return;
        }
        touched = true;
        long room = ceiling - length;
        if (room > 0) {
            bytes.write(buffer, offset, (int) Math.min(count, room));
        }
        length += count;
    }

    /** Records that the stream was used, even by a read that returned nothing. */
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
