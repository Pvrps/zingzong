package io.capture.agent.serialize;

/**
 * Limits. Immutable; build with the fluent {@code with*} methods. The defaults are the
 * product: nothing here needs setting per application.
 */
public final class SerializerConfig {

    public static final int DEFAULT_MAX_DEPTH = 12;
    public static final int DEFAULT_MAX_ELEMENTS = 500;
    public static final int DEFAULT_MAX_STRING_LENGTH = 64 * 1024;
    public static final int DEFAULT_MAX_BYTES = 4096;

    /** Nesting of objects/collections below the root before a placeholder is emitted. */
    public final int maxDepth;
    /** Elements per collection, map or array before the rest is truncated. */
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
