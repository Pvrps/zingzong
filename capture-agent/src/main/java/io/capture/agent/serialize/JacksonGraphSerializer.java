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
 * Builds Jackson {@link JsonNode} trees by walking object graphs field by field, raw: what
 * crossed the boundary, as it was. The only transformations are the structural rules in
 * {@link Placeholders}, each marked in the output; the safety ceilings in
 * {@link SerializerConfig} are never meant to be reached and leave counted markers when they are.
 *
 * This deliberately does <em>not</em> route objects through {@code ObjectMapper}'s bean
 * serialization: Jackson's serializer cache keys on {@code Class} strongly (pinning
 * redeployed loaders), getters would have to be suppressed per type, identity and depth
 * rules would need mixins and a wrapping generator, and lazy JPA wrappers must be
 * recognised before any collection method runs. Jackson provides the tree model, the scalar
 * nodes and the output format. Per-type state lives in {@link TypeInfo} (a {@code ClassValue}).
 *
 * Fields only, never getters. Nothing here throws into the caller: a failure becomes a
 * marked {@code @unserializable} node and a warning.
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
            return Placeholders.unserializable(value, "serializer failure: " + t, null);
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
            String reason = describeFailure(t);
            ctx.warn(reason, value.getClass().getName());
            return Placeholders.unserializable(value, reason, safeToString(value));
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

        if (info.handleKind != null) {
            ctx.warn("runtime handle (" + info.handleKind + "), recorded as reference", info.type.getName());
            return Placeholders.handle(value, info.handleKind, safeToString(value, info));
        }

        if (ctx.depth() >= config.maxDepth) {
            String reason = "depth ceiling " + config.maxDepth + " reached; subtree dropped";
            ctx.warn(reason, info.type.getName());
            return Placeholders.unserializable(value, reason, safeToString(value, info));
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
                String reason = "cyclic reference to a container being written";
                ctx.warn(reason, info.type.getName());
                return Placeholders.unserializable(value, reason, null);
            }
            // A container seen before but complete: written again in full, duplicates are fine.
        }

        if (value instanceof Optional) {
            return writeOptional((Optional<?>) value, ctx);
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
        if (value instanceof Throwable) {
            return writeThrowable((Throwable) value, info, ctx);
        }
        return writeObject(value, info, ctx);
    }

    private ObjectNode writeObject(Object value, TypeInfo info, SerializationContext ctx) {
        ObjectNode node = NODES.objectNode();
        node.put(Placeholders.TYPE, info.type.getName());
        if (!info.serializable) {
            // Walked all the same; the exporter and replay need to know it cannot cross T3.
            node.put(Placeholders.SERIALIZABLE, false);
        }
        SerializationContext.Tracked tracked = ctx.track(value, node);
        ctx.enter();
        try {
            writeFields(value, info, node, ctx);
        } finally {
            ctx.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private void writeFields(Object value, TypeInfo info, ObjectNode node, SerializationContext ctx) {
        for (TypeInfo.FieldInfo field : info.fields) {
            if (!field.walked()) {
                Placeholders.skipped(node, field.name, field.skipReason);
                continue;
            }
            ctx.pushField(field.name);
            try {
                node.set(field.name, writeField(value, field, ctx));
            } catch (Throwable t) {
                String reason = describeFailure(t);
                ctx.warn(reason, field.field.getType().getName());
                node.set(field.name, Placeholders.unserializable(null, reason, null));
            } finally {
                ctx.pop();
            }
        }
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

    private JsonNode writeOptional(Optional<?> optional, SerializationContext ctx) {
        ObjectNode node = NODES.objectNode();
        node.put(Placeholders.TYPE, Optional.class.getName());
        node.put("present", optional.isPresent());
        if (optional.isPresent()) {
            ctx.pushField("value");
            ctx.enter();
            try {
                node.set("value", write(optional.get(), ctx));
            } finally {
                ctx.leave();
                ctx.pop();
            }
        }
        return node;
    }

    private JsonNode writeArray(Object array, SerializationContext ctx) {
        if (array instanceof byte[]) {
            byte[] bytes = (byte[]) array;
            int kept = Math.min(bytes.length, config.maxBytes);
            byte[] shown = kept == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, kept);
            int dropped = bytes.length - kept;
            if (dropped > 0) {
                ctx.warn("byte[] ceiling " + config.maxBytes + " reached; " + dropped + " of " + bytes.length + " bytes dropped", "byte[]");
            }
            return Placeholders.bytes(Base64.getEncoder().encodeToString(shown), bytes.length, dropped);
        }
        if (array instanceof char[]) {
            return Scalars.write(new String((char[]) array), ctx);
        }
        ArrayNode node = NODES.arrayNode();
        SerializationContext.Tracked tracked = ctx.track(array, null);
        ctx.enter();
        try {
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                if (i >= config.maxElements) {
                    int dropped = length - i;
                    ctx.warn("element ceiling " + config.maxElements + " reached; " + dropped + " of " + length + " elements dropped", array.getClass().getName());
                    node.add(Placeholders.truncatedElements("elements", dropped));
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
                    int dropped = size - i;
                    ctx.warn("element ceiling " + config.maxElements + " reached; " + dropped + " of " + size + " elements dropped", collection.getClass().getName());
                    node.add(Placeholders.truncatedElements("elements", dropped));
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
                        int dropped = map.size() - config.maxElements;
                        ctx.warn("element ceiling " + config.maxElements + " reached; " + dropped + " of " + map.size() + " entries dropped", info.type.getName());
                        node.set(Placeholders.TRUNCATED, NODES.numberNode(dropped));
                        node.put(Placeholders.WHAT, "entries");
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
                    int dropped = map.size() - i;
                    ctx.warn("element ceiling " + config.maxElements + " reached; " + dropped + " of " + map.size() + " entries dropped", info.type.getName());
                    entries.add(Placeholders.truncatedElements("entries", dropped));
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

    /**
     * A Throwable is walked like any object (its own fields included: application exceptions
     * carry data), plus the stack trace in full. Two JVM-internal fields of Throwable itself
     * are listed as skipped: {@code backtrace} (native frame data behind {@code stackTrace})
     * and {@code depth}. The {@code cause == this} sentinel reads as "no cause".
     */
    private ObjectNode writeThrowable(Throwable t, TypeInfo info, SerializationContext ctx) {
        ObjectNode node = NODES.objectNode();
        node.put(Placeholders.TYPE, info.type.getName());
        SerializationContext.Tracked tracked = ctx.track(t, node);
        ctx.enter();
        try {
            for (TypeInfo.FieldInfo field : info.fields) {
                if (!field.walked()) {
                    Placeholders.skipped(node, field.name, field.skipReason);
                    continue;
                }
                boolean declaredByThrowable = field.field.getDeclaringClass() == Throwable.class;
                if (declaredByThrowable && (field.name.equals("backtrace") || field.name.equals("depth"))) {
                    Placeholders.skipped(node, field.name, "JVM-internal frame data; see stackTrace");
                    continue;
                }
                if (declaredByThrowable && field.name.equals("stackTrace")) {
                    continue; // written below from getStackTrace(), which materialises it
                }
                ctx.pushField(field.name);
                try {
                    Object value = field.field.get(t);
                    if (declaredByThrowable && field.name.equals("cause") && value == t) {
                        value = null;
                    }
                    node.set(field.name, write(value, ctx));
                } catch (Throwable e) {
                    String reason = describeFailure(e);
                    ctx.warn(reason, field.field.getType().getName());
                    node.set(field.name, Placeholders.unserializable(null, reason, null));
                } finally {
                    ctx.pop();
                }
            }
            ctx.pushField("stackTrace");
            try {
                node.set("stackTrace", write(t.getStackTrace(), ctx));
            } finally {
                ctx.pop();
            }
        } finally {
            ctx.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String safeToString(Object value) {
        return safeToString(value, TypeInfo.of(value.getClass()));
    }

    /**
     * toString() for markers, except on JPA entities and lazy wrappers, whose toString may
     * touch lazy state: those get null (the marker's identity still names the object).
     */
    private static String safeToString(Object value, TypeInfo info) {
        if (info.entity || info.lazyKind != TypeInfo.LazyKind.NONE) {
            return null;
        }
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
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
