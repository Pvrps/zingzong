package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

/**
 * The safety ceilings of the serializer. Each keeps one pathological value from exhausting the
 * server's heap or the trace file; none is reachable by a real payload, and hitting one always
 * leaves a {@code @truncated} marker with counts. Lower values are for tests only.
 */
public final class SerializationCeilings {

    /** Nesting of objects and containers; only a linked structure walked link by link reaches 64. */
    public static final int DEFAULT_MAX_DEPTH = 64;
    /** Elements per collection, map or array; 100k elements is tens of megabytes of trace. */
    public static final int DEFAULT_MAX_ELEMENTS = 100_000;
    /** Characters per string; 16 Mi chars is a file, not a field, and 32 MiB of heap for one node. */
    public static final int DEFAULT_MAX_STRING_LENGTH = 16 * 1024 * 1024;
    /** Bytes per byte[]; 64 MiB base64-encodes to 85 MiB in one node. */
    public static final int DEFAULT_MAX_BYTES = 64 * 1024 * 1024;

    public final int maxDepth;
    public final int maxElements;
    public final int maxStringLength;
    public final int maxBytes;

    public SerializationCeilings() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_ELEMENTS, DEFAULT_MAX_STRING_LENGTH, DEFAULT_MAX_BYTES);
    }

    private SerializationCeilings(int maxDepth, int maxElements, int maxStringLength, int maxBytes) {
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
        this.maxStringLength = maxStringLength;
        this.maxBytes = maxBytes;
    }

    public SerializationCeilings withMaxDepth(int value) {
        return new SerializationCeilings(value, maxElements, maxStringLength, maxBytes);
    }

    public SerializationCeilings withMaxElements(int value) {
        return new SerializationCeilings(maxDepth, value, maxStringLength, maxBytes);
    }

    public SerializationCeilings withMaxStringLength(int value) {
        return new SerializationCeilings(maxDepth, maxElements, value, maxBytes);
    }

    public SerializationCeilings withMaxBytes(int value) {
        return new SerializationCeilings(maxDepth, maxElements, maxStringLength, value);
    }
}
