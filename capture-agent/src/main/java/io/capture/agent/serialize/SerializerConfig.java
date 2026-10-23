package io.capture.agent.serialize;

/**
 * Safety ceilings. Each exists to keep one pathological value from exhausting the server's
 * heap or the trace file; none is meant to be reached by a real payload, and hitting one
 * always leaves a {@code @truncated} marker with counts. Lower values are for tests only.
 */
public final class SerializerConfig {

    /**
     * Nesting of objects and containers. A real DTO graph is a few levels deep; 64 is only
     * reachable by a linked structure walked link by link (a long chain or a runaway
     * reference graph through framework internals).
     */
    public static final int DEFAULT_MAX_DEPTH = 64;
    /** Elements per collection, map or array. 100k elements is tens of megabytes of trace. */
    public static final int DEFAULT_MAX_ELEMENTS = 100_000;
    /** Characters per string. 16 Mi chars is a file, not a field; and 32 MiB of heap for one node. */
    public static final int DEFAULT_MAX_STRING_LENGTH = 16 * 1024 * 1024;
    /** Bytes per byte[]. 64 MiB base64-encodes to 85 MiB in one node. */
    public static final int DEFAULT_MAX_BYTES = 64 * 1024 * 1024;

    public final int maxDepth;
    public final int maxElements;
    public final int maxStringLength;
    public final int maxBytes;
    public final TypeAllowList typeAllowList;

    public SerializerConfig() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_ELEMENTS, DEFAULT_MAX_STRING_LENGTH, DEFAULT_MAX_BYTES, TypeAllowList.allowAll());
    }

    private SerializerConfig(int maxDepth, int maxElements, int maxStringLength, int maxBytes, TypeAllowList typeAllowList) {
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
        this.maxStringLength = maxStringLength;
        this.maxBytes = maxBytes;
        this.typeAllowList = typeAllowList;
    }

    public SerializerConfig withMaxDepth(int value) {
        return new SerializerConfig(value, maxElements, maxStringLength, maxBytes, typeAllowList);
    }

    public SerializerConfig withMaxElements(int value) {
        return new SerializerConfig(maxDepth, value, maxStringLength, maxBytes, typeAllowList);
    }

    public SerializerConfig withMaxStringLength(int value) {
        return new SerializerConfig(maxDepth, maxElements, value, maxBytes, typeAllowList);
    }

    public SerializerConfig withMaxBytes(int value) {
        return new SerializerConfig(maxDepth, maxElements, maxStringLength, value, typeAllowList);
    }

    public SerializerConfig withTypeAllowList(TypeAllowList value) {
        return new SerializerConfig(maxDepth, maxElements, maxStringLength, maxBytes, value);
    }
}
