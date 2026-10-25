package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Builds Jackson trees by walking object graphs field by field, never through getters or an
 * {@code ObjectMapper}; Jackson supplies the tree model only. Repeated objects become
 * {@code @ref}, lazy values are read for their state and never loaded, runtime handles become
 * {@code @handle}, and every ceiling in the {@link SerializationCeilings} leaves {@code @truncated}.
 * A failure anywhere becomes an {@code @unserializable} node and a warning, never an exception
 * for the caller.
 */
public final class JacksonGraphSerializer implements GraphSerializer {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SerializationCeilings ceilings;
    private final TypeInfoCache typeInfos;
    private final JmsMessageView jmsMessages;

    public JacksonGraphSerializer(SerializationCeilings ceilings, TypeInfoCache typeInfos) {
        this.ceilings = ceilings;
        this.typeInfos = typeInfos;
        this.jmsMessages = new JmsMessageView(this);
    }

    public SerializationCeilings ceilings() {
        return ceilings;
    }

    @Override
    public JsonNode serialize(Object value, Class<?> declaredType, SerializationContext context) {
        try {
            return write(value, context);
        } catch (Throwable failure) {
            // Last line of defence; write() already catches per sub-tree.
            safeWarn(context, "serializer failure: " + failure, value);
            return Marker.unserializable(value, "serializer failure: " + failure, null).toNode();
        }
    }

    /** A nested value written as part of a composite view, such as a JMS message. */
    JsonNode writeValue(Object value, SerializationContext context) {
        return write(value, context);
    }

    private JsonNode write(Object value, SerializationContext context) {
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            JsonNode scalar = Scalars.write(value, context);
            return scalar != null ? scalar : writeStructured(value, context);
        } catch (Throwable failure) {
            String reason = describeFailure(failure);
            context.warn(reason, value.getClass().getName());
            return Marker.unserializable(value, reason, safeToString(value)).toNode();
        }
    }

    private JsonNode writeStructured(Object value, SerializationContext context) {
        TypeInfo info = typeInfos.of(value.getClass());

        LazyState lazy = LazyState.of(value, info);
        if (lazy == LazyState.UNLOADED || lazy == LazyState.UNKNOWN) {
            if (lazy == LazyState.UNKNOWN) {
                context.warn("lazy state unknown, not touched", info.type.getName());
            }
            return Marker.lazy().toNode();
        }
        if (lazy == LazyState.LOADED) {
            Object loaded = LazyState.loadedValue(value, info);
            if (loaded != value) {
                return write(loaded, context);
            }
        }

        if (info.handleKind != null) {
            context.warn("runtime handle (" + info.handleKind + "), recorded as reference", info.type.getName());
            return Marker.handle(value, info.handleKind, safeToString(value, info)).toNode();
        }

        if (context.depth() >= ceilings.maxDepth) {
            String reason = "depth ceiling " + ceilings.maxDepth + " reached; subtree dropped";
            context.warn(reason, info.type.getName());
            return Marker.unserializable(value, reason, safeToString(value, info)).toNode();
        }

        SerializationContext.Tracked seen = context.seen(value);
        if (seen != null) {
            if (seen.node != null) {
                int id = context.idFor(seen);
                if (!seen.node.has(Marker.Kind.ID.key())) {
                    Marker.id(id).writeTo(seen.node);
                }
                return Marker.ref(id).toNode();
            }
            if (seen.inProgress) {
                String reason = "cyclic reference to a container being written";
                context.warn(reason, info.type.getName());
                return Marker.unserializable(value, reason, null).toNode();
            }
            // A container seen before but complete: written again in full, duplicates are fine.
        }

        if (value instanceof Optional) {
            return writeOptional((Optional<?>) value, context);
        }
        if (info.type.isArray()) {
            return writeArray(value, context);
        }
        if (value instanceof Collection) {
            return writeCollection((Collection<?>) value, context);
        }
        if (value instanceof Map) {
            return writeMap((Map<?, ?>) value, info, context);
        }
        if (value instanceof Throwable) {
            return writeThrowable((Throwable) value, info, context);
        }
        if (info.jmsMessage) {
            return jmsMessages.write(value, info, context);
        }
        return writeObject(value, info, context);
    }

    private ObjectNode writeObject(Object value, TypeInfo info, SerializationContext context) {
        ObjectNode node = NODES.objectNode();
        Marker.type(info.type.getName()).writeTo(node);
        if (!info.serializable) {
            Marker.notSerializable().writeTo(node);
        }
        SerializationContext.Tracked tracked = context.track(value, node);
        context.enter();
        try {
            for (TypeInfo.FieldInfo field : info.fields) {
                if (!field.walked()) {
                    Marker.skipped(field.name, field.skipReason).writeTo(node);
                    continue;
                }
                writeField(value, field, node, context);
            }
        } finally {
            context.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private void writeField(Object owner, TypeInfo.FieldInfo field, ObjectNode node, SerializationContext context) {
        context.pushField(field.name);
        try {
            node.set(field.name, fieldValue(owner, field, context));
        } catch (Throwable failure) {
            String reason = describeFailure(failure);
            context.warn(reason, field.field.getType().getName());
            node.set(field.name, Marker.unserializable(null, reason, null).toNode());
        } finally {
            context.pop();
        }
    }

    private JsonNode fieldValue(Object owner, TypeInfo.FieldInfo field, SerializationContext context) throws IllegalAccessException {
        if (field.lazyHolder != null) {
            Object holder = field.lazyHolder.get(owner);
            LazyState state = LazyState.ofHolder(holder, typeInfos);
            if (state == LazyState.UNLOADED || state == LazyState.UNKNOWN) {
                return Marker.lazy().toNode();
            }
            if (state == LazyState.LOADED) {
                return write(LazyState.loadedValue(holder, typeInfos.of(holder.getClass())), context);
            }
        }
        return write(field.field.get(owner), context);
    }

    private JsonNode writeOptional(Optional<?> optional, SerializationContext context) {
        ObjectNode node = NODES.objectNode();
        Marker.type(Optional.class.getName()).writeTo(node);
        node.put("present", optional.isPresent());
        if (optional.isPresent()) {
            context.pushField("value");
            context.enter();
            try {
                node.set("value", write(optional.get(), context));
            } finally {
                context.leave();
                context.pop();
            }
        }
        return node;
    }

    private JsonNode writeArray(Object array, SerializationContext context) {
        if (array instanceof byte[]) {
            byte[] bytes = (byte[]) array;
            int kept = Math.min(bytes.length, ceilings.maxBytes);
            byte[] shown = kept == bytes.length ? bytes : Arrays.copyOf(bytes, kept);
            int dropped = bytes.length - kept;
            if (dropped > 0) {
                context.warn("byte[] ceiling " + ceilings.maxBytes + " reached; " + dropped + " of " + bytes.length + " bytes dropped", "byte[]");
            }
            return Marker.bytes(Base64.getEncoder().encodeToString(shown), bytes.length, dropped).toNode();
        }
        if (array instanceof char[]) {
            return Scalars.write(new String((char[]) array), context);
        }
        ArrayNode node = NODES.arrayNode();
        SerializationContext.Tracked tracked = context.track(array, null);
        context.enter();
        try {
            int length = Array.getLength(array);
            for (int index = 0; index < length; index++) {
                if (index >= ceilings.maxElements) {
                    int dropped = length - index;
                    context.warn("element ceiling " + ceilings.maxElements + " reached; " + dropped + " of " + length + " elements dropped", array.getClass().getName());
                    node.add(Marker.truncated("elements", dropped).toNode());
                    break;
                }
                context.pushIndex(index);
                try {
                    node.add(write(Array.get(array, index), context));
                } finally {
                    context.pop();
                }
            }
        } finally {
            context.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private JsonNode writeCollection(Collection<?> collection, SerializationContext context) {
        ArrayNode node = NODES.arrayNode();
        SerializationContext.Tracked tracked = context.track(collection, null);
        context.enter();
        try {
            int size = collection.size();
            int index = 0;
            for (Iterator<?> elements = collection.iterator(); elements.hasNext(); index++) {
                Object element = elements.next();
                if (index >= ceilings.maxElements) {
                    int dropped = size - index;
                    context.warn("element ceiling " + ceilings.maxElements + " reached; " + dropped + " of " + size + " elements dropped", collection.getClass().getName());
                    node.add(Marker.truncated("elements", dropped).toNode());
                    break;
                }
                context.pushIndex(index);
                try {
                    node.add(write(element, context));
                } finally {
                    context.pop();
                }
            }
        } finally {
            context.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private JsonNode writeMap(Map<?, ?> map, TypeInfo info, SerializationContext context) {
        boolean scalarKeys = map.keySet().stream().allMatch(key -> key instanceof String || key instanceof Enum
                || key instanceof Number || key instanceof Boolean || key instanceof Character);
        SerializationContext.Tracked tracked = context.track(map, null);
        context.enter();
        try {
            return scalarKeys ? writeStringKeyedMap(map, info, context) : writeEntryMap(map, info, context);
        } finally {
            context.leave();
            tracked.inProgress = false;
        }
    }

    private ObjectNode writeStringKeyedMap(Map<?, ?> map, TypeInfo info, SerializationContext context) {
        ObjectNode node = NODES.objectNode();
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index++ >= ceilings.maxElements) {
                int dropped = map.size() - ceilings.maxElements;
                context.warn("element ceiling " + ceilings.maxElements + " reached; " + dropped + " of " + map.size() + " entries dropped", info.type.getName());
                Marker.truncated("entries", dropped).writeTo(node);
                break;
            }
            String key = entry.getKey() instanceof Enum ? ((Enum<?>) entry.getKey()).name() : String.valueOf(entry.getKey());
            context.pushKey(key);
            try {
                node.set(key, write(entry.getValue(), context));
            } finally {
                context.pop();
            }
        }
        return node;
    }

    private ObjectNode writeEntryMap(Map<?, ?> map, TypeInfo info, SerializationContext context) {
        ObjectNode node = NODES.objectNode();
        Marker.type(info.type.getName()).writeTo(node);
        ArrayNode entries = NODES.arrayNode();
        Marker.entries(entries).writeTo(node);
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= ceilings.maxElements) {
                int dropped = map.size() - index;
                context.warn("element ceiling " + ceilings.maxElements + " reached; " + dropped + " of " + map.size() + " entries dropped", info.type.getName());
                entries.add(Marker.truncated("entries", dropped).toNode());
                break;
            }
            context.pushIndex(index++);
            ObjectNode pair = entries.addObject();
            try {
                context.pushField("key");
                pair.set("key", write(entry.getKey(), context));
                context.pop();
                context.pushField("value");
                pair.set("value", write(entry.getValue(), context));
                context.pop();
            } finally {
                context.pop();
            }
        }
        return node;
    }

    /**
     * A Throwable is walked like any object, its own fields included because application
     * exceptions carry data, plus the stack trace in full. Two JVM-internal fields of Throwable
     * itself are listed as skipped: {@code backtrace} (native frame data behind {@code stackTrace})
     * and {@code depth}. The {@code cause == this} sentinel reads as "no cause".
     */
    private ObjectNode writeThrowable(Throwable throwable, TypeInfo info, SerializationContext context) {
        ObjectNode node = NODES.objectNode();
        Marker.type(info.type.getName()).writeTo(node);
        SerializationContext.Tracked tracked = context.track(throwable, node);
        context.enter();
        try {
            for (TypeInfo.FieldInfo field : info.fields) {
                if (!field.walked()) {
                    Marker.skipped(field.name, field.skipReason).writeTo(node);
                    continue;
                }
                boolean declaredByThrowable = field.field.getDeclaringClass() == Throwable.class;
                if (declaredByThrowable && (field.name.equals("backtrace") || field.name.equals("depth"))) {
                    Marker.skipped(field.name, "JVM-internal frame data; see stackTrace").writeTo(node);
                    continue;
                }
                if (declaredByThrowable && field.name.equals("stackTrace")) {
                    continue; // written below from getStackTrace(), which materialises it
                }
                context.pushField(field.name);
                try {
                    Object value = field.field.get(throwable);
                    if (declaredByThrowable && field.name.equals("cause") && value == throwable) {
                        value = null;
                    }
                    node.set(field.name, write(value, context));
                } catch (Throwable failure) {
                    String reason = describeFailure(failure);
                    context.warn(reason, field.field.getType().getName());
                    node.set(field.name, Marker.unserializable(null, reason, null).toNode());
                } finally {
                    context.pop();
                }
            }
            context.pushField("stackTrace");
            try {
                node.set("stackTrace", write(throwable.getStackTrace(), context));
            } finally {
                context.pop();
            }
        } finally {
            context.leave();
            tracked.inProgress = false;
        }
        return node;
    }

    private String safeToString(Object value) {
        return safeToString(value, typeInfos.of(value.getClass()));
    }

    /**
     * {@code toString()} for markers, except on JPA entities and lazy wrappers, whose toString may
     * touch lazy state: those get null, and the marker's identity still names the object.
     */
    private static String safeToString(Object value, TypeInfo info) {
        if (info.entity || info.lazyKind != TypeInfo.LazyKind.NONE) {
            return null;
        }
        try {
            return String.valueOf(value);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static void safeWarn(SerializationContext context, String reason, Object value) {
        try {
            context.warn(reason, value == null ? "null" : value.getClass().getName());
        } catch (Throwable ignored) {
            // nothing left to do
        }
    }
}
