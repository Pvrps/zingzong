package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Builds Jackson {@link JsonNode} trees by walking object graphs field by field.
 *
 * This deliberately does <em>not</em> route objects through {@code ObjectMapper}'s bean
 * serialization: Jackson's serializer cache keys on {@code Class} strongly (pinning
 * redeployed loaders), getters would have to be suppressed per type, identity and depth
 * limits would need mixins and a wrapping generator, and lazy JPA wrappers must be
 * recognised before any collection method runs. Walking the graph here keeps every one of
 * those rules in one place; Jackson provides the tree model, the scalar nodes and the
 * output format. Per-type state lives in {@link TypeInfo} (a {@code ClassValue}).
 *
 * Fields only, never getters. Static, transient, synthetic and EclipseLink weaving fields
 * are skipped. Nothing here throws into the caller: every failure becomes a placeholder
 * and a warning.
 */
public final class JacksonGraphSerializer implements GraphSerializer {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SerializerConfig config;
    private final GraphReader reader;

    public JacksonGraphSerializer() {
        this(new SerializerConfig());
    }

    public JacksonGraphSerializer(SerializerConfig config) {
        this.config = config;
        this.reader = new GraphReader(config);
    }

    public SerializerConfig config() {
        return config;
    }

    @Override
    public JsonNode serialize(Object value, Class<?> declaredType, SerializationContext ctx) {
        try {
            return write(value, ctx);
        } catch (Throwable t) {
            // Last line of defence; write() already catches per sub-tree.
            safeWarn(ctx, "serializer failure: " + t, value);
            return Placeholders.unserializable(value, null);
        }
    }

    @Override
    public Object deserialize(JsonNode node, Type declaredType, ClassLoader loader) throws GraphException {
        return reader.read(node, declaredType, loader);
    }

    // ---- writing -------------------------------------------------------------------------

    private JsonNode write(Object value, SerializationContext ctx) {
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            JsonNode scalar = Scalars.write(value, ctx);
            if (scalar != null) {
                return scalar;
            }
            return writeStructured(value, ctx);
        } catch (Throwable t) {
            ctx.warn(describeFailure(t), value.getClass().getName());
            return Placeholders.unserializable(value, safeToString(value));
        }
    }

    private JsonNode writeStructured(Object value, SerializationContext ctx) {
        TypeInfo info = TypeInfo.of(value.getClass());

        Lazy.State lazy = Lazy.stateOf(value, info);
        if (lazy == Lazy.State.UNLOADED || lazy == Lazy.State.UNKNOWN) {
            if (lazy == Lazy.State.UNKNOWN) {
                ctx.warn("lazy state unknown, not touched", info.type.getName());
            }
            return Placeholders.lazy(false);
        }
        if (lazy == Lazy.State.LOADED) {
            Object loaded = Lazy.loadedValue(value, info);
            if (loaded != value) {
                return write(loaded, ctx);
            }
        }

        if (info.infrastructure) {
            ctx.warn("infrastructure type, not captured", info.type.getName());
            return Placeholders.unserializable(value, identity(value));
        }

        if (ctx.depth() >= config.maxDepth) {
            ctx.warn("depth limit " + config.maxDepth, info.type.getName());
            return Placeholders.unserializable(value, safeToString(value, info));
        }

        SerializationContext.Tracked seen = ctx.seen(value);
        if (seen != null) {
            if (seen.node != null) {
                int id = ctx.idFor(seen);
                if (!seen.node.has(Placeholders.ID)) {
                    seen.node.put(Placeholders.ID, id);
                }
                return Placeholders.ref(id);
            }
            if (seen.inProgress) {
                ctx.warn("cyclic collection reference", info.type.getName());
                return Placeholders.unserializable(value, identity(value));
            }
            // A collection seen before but complete: serialize it again, duplicates are fine.
        }

        if (value instanceof Throwable) {
            return writeThrowable((Throwable) value, ctx);
        }
        if (value instanceof Optional) {
            Optional<?> optional = (Optional<?>) value;
            return optional.isPresent() ? write(optional.get(), ctx) : NullNode.getInstance();
        }
        if (info.type.isArray()) {
            return writeArray(value, ctx);
        }
        if (value instanceof Collection) {
            return writeCollection((Collection<?>) value, ctx);
        }
        if (value instanceof Map) {
            return writeMap((Map<?, ?>) value, info, ctx);
        }
        return writeObject(value, info, ctx);
    }

    private ObjectNode writeObject(Object value, TypeInfo info, SerializationContext ctx) {
        ObjectNode node = NODES.objectNode();
        node.put(Placeholders.TYPE, info.type.getName());
        if (!info.serializable) {
            // Captured all the same; the exporter and replay need to know it cannot cross T3.
            node.put(Placeholders.SERIALIZABLE, false);
        }
        SerializationContext.Tracked tracked = ctx.track(value, node);
        ctx.enter();
        try {
            for (TypeInfo.FieldInfo field : info.fields) {
                ctx.pushField(field.name);
                try {
                    node.set(field.name, writeField(value, field, ctx));
                } catch (Throwable t) {
                    ctx.warn(describeFailure(t), field.field.getType().getName());
                    node.set(field.name, Placeholders.unserializable(null, null));
                } finally {
                    ctx.pop();
                }
            }
        } finally {
            ctx.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private JsonNode writeField(Object owner, TypeInfo.FieldInfo field, SerializationContext ctx) throws IllegalAccessException {
        if (field.lazyHolder != null) {
            Object holder = field.lazyHolder.get(owner);
            Lazy.State state = Lazy.holderState(holder);
            if (state == Lazy.State.UNLOADED || state == Lazy.State.UNKNOWN) {
                return Placeholders.lazy(false);
            }
            if (state == Lazy.State.LOADED) {
                return write(Lazy.loadedValue(holder, TypeInfo.of(holder.getClass())), ctx);
            }
        }
        return write(field.field.get(owner), ctx);
    }

    private JsonNode writeArray(Object array, SerializationContext ctx) {
        if (array instanceof byte[]) {
            byte[] bytes = (byte[]) array;
            boolean truncated = bytes.length > config.maxBytes;
            byte[] shown = truncated ? java.util.Arrays.copyOf(bytes, config.maxBytes) : bytes;
            if (truncated) {
                ctx.warn("byte[] truncated from " + bytes.length + " to " + config.maxBytes + " bytes", "byte[]");
            }
            return Placeholders.bytes(Base64.getEncoder().encodeToString(shown), bytes.length, truncated);
        }
        if (array instanceof char[]) {
            return TextNode.valueOf(new String((char[]) array));
        }
        ArrayNode node = NODES.arrayNode();
        SerializationContext.Tracked tracked = ctx.track(array, null);
        ctx.enter();
        try {
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                if (i >= config.maxElements) {
                    ctx.warn("array truncated at " + config.maxElements + " of " + length + " elements", array.getClass().getName());
                    node.add(Placeholders.truncated(length - i));
                    break;
                }
                ctx.pushIndex(i);
                try {
                    node.add(write(Array.get(array, i), ctx));
                } finally {
                    ctx.pop();
                }
            }
        } finally {
            ctx.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private JsonNode writeCollection(Collection<?> collection, SerializationContext ctx) {
        ArrayNode node = NODES.arrayNode();
        SerializationContext.Tracked tracked = ctx.track(collection, null);
        ctx.enter();
        try {
            int i = 0;
            int size = collection.size();
            for (Iterator<?> it = collection.iterator(); it.hasNext(); i++) {
                Object element = it.next();
                if (i >= config.maxElements) {
                    ctx.warn("collection truncated at " + config.maxElements + " of " + size + " elements", collection.getClass().getName());
                    node.add(Placeholders.truncated(size - i));
                    break;
                }
                ctx.pushIndex(i);
                try {
                    node.add(write(element, ctx));
                } finally {
                    ctx.pop();
                }
            }
        } finally {
            ctx.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private JsonNode writeMap(Map<?, ?> map, TypeInfo info, SerializationContext ctx) {
        boolean stringKeys = true;
        for (Object key : map.keySet()) {
            if (!(key instanceof String || key instanceof Enum || key instanceof Number || key instanceof Boolean || key instanceof Character)) {
                stringKeys = false;
                break;
            }
        }
        SerializationContext.Tracked tracked = ctx.track(map, null);
        ctx.enter();
        try {
            if (stringKeys) {
                ObjectNode node = NODES.objectNode();
                int i = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (i++ >= config.maxElements) {
                        ctx.warn("map truncated at " + config.maxElements + " of " + map.size() + " entries", info.type.getName());
                        node.set(Placeholders.TRUNCATED, NODES.numberNode(map.size() - config.maxElements));
                        break;
                    }
                    String key = entry.getKey() instanceof Enum ? ((Enum<?>) entry.getKey()).name() : String.valueOf(entry.getKey());
                    ctx.pushKey(key);
                    try {
                        node.set(key, write(entry.getValue(), ctx));
                    } finally {
                        ctx.pop();
                    }
                }
                return node;
            }
            ObjectNode node = NODES.objectNode();
            node.put(Placeholders.TYPE, info.type.getName());
            ArrayNode entries = node.putArray(Placeholders.ENTRIES);
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (i >= config.maxElements) {
                    ctx.warn("map truncated at " + config.maxElements + " of " + map.size() + " entries", info.type.getName());
                    entries.add(Placeholders.truncated(map.size() - i));
                    break;
                }
                ctx.pushIndex(i++);
                ObjectNode pair = entries.addObject();
                try {
                    ctx.pushField("key");
                    pair.set("key", write(entry.getKey(), ctx));
                    ctx.pop();
                    ctx.pushField("value");
                    pair.set("value", write(entry.getValue(), ctx));
                    ctx.pop();
                } finally {
                    ctx.pop();
                }
            }
            return node;
        } finally {
            ctx.leave();
            tracked.inProgress = false;
        }
    }

    private JsonNode writeThrowable(Throwable t, SerializationContext ctx) {
        ObjectNode node = NODES.objectNode();
        node.put(Placeholders.TYPE, t.getClass().getName());
        ctx.track(t, node).inProgress = false;
        node.put("message", t.getMessage());
        ArrayNode frames = node.putArray("stackTrace");
        StackTraceElement[] trace = t.getStackTrace();
        for (int i = 0; i < trace.length && i < 10; i++) {
            frames.add(trace[i].toString());
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            ctx.pushField("cause");
            ctx.enter();
            try {
                node.set("cause", ctx.depth() >= config.maxDepth
                        ? Placeholders.unserializable(cause, cause.toString())
                        : write(cause, ctx));
            } finally {
                ctx.leave();
                ctx.pop();
            }
        }
        return node;
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String safeToString(Object value) {
        return safeToString(value, TypeInfo.of(value.getClass()));
    }

    /**
     * toString() for placeholders, except on JPA entities and lazy wrappers, whose toString
     * may touch lazy state. Those get the identity form instead.
     */
    private static String safeToString(Object value, TypeInfo info) {
        if (info.entity || info.lazyKind != TypeInfo.LazyKind.NONE) {
            return identity(value);
        }
        try {
            String s = String.valueOf(value);
            return s.length() > 512 ? s.substring(0, 512) + "..." : s;
        } catch (Throwable t) {
            return identity(value);
        }
    }

    private static String identity(Object value) {
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String describeFailure(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static void safeWarn(SerializationContext ctx, String reason, Object value) {
        try {
            ctx.warn(reason, value == null ? "null" : value.getClass().getName());
        } catch (Throwable ignored) {
            // nothing left to do
        }
    }
}
