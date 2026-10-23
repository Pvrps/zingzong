package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rebuilds objects from the trees {@link JacksonGraphSerializer} writes. Classes come from
 * the supplied loader and must pass the {@link TypeAllowList}. A placeholder ({@code @lazy},
 * {@code @unserializable}) leaves the field at its constructor default, or reads as null at
 * the top level. {@code @ref} resolves against objects already created in the same call,
 * which works because an object is registered before its fields are filled.
 */
final class GraphReader {

    private final SerializerConfig config;

    GraphReader(SerializerConfig config) {
        this.config = config;
    }

    Object read(JsonNode node, Type declaredType, ClassLoader loader) throws GraphException {
        return new Pass(loader).read(node, declaredType == null ? Object.class : declaredType);
    }

    private final class Pass {
        private final ClassLoader loader;
        private final Map<Integer, Object> byId = new HashMap<Integer, Object>();

        Pass(ClassLoader loader) {
            this.loader = loader;
        }

        Object read(JsonNode node, Type declaredType) throws GraphException {
            Class<?> raw = rawClass(declaredType);
            if (node == null || node.isNull()) {
                return Scalars.read(node, raw);
            }
            if (node.isObject()) {
                return readObjectNode(node, declaredType, raw);
            }
            if (node.isArray()) {
                return readArrayNode(node, declaredType, raw);
            }
            return Scalars.read(node, raw);
        }

        private Object readObjectNode(JsonNode node, Type declaredType, Class<?> raw) throws GraphException {
            if (node.has(Placeholders.REF)) {
                int id = node.get(Placeholders.REF).asInt();
                if (!byId.containsKey(id)) {
                    throw new GraphException("unresolved @ref " + id);
                }
                return byId.get(id);
            }
            if (node.has(Placeholders.LAZY) || node.has(Placeholders.UNSERIALIZABLE)) {
                return null;
            }
            if (node.has(Placeholders.BYTES)) {
                return Base64.getDecoder().decode(node.get(Placeholders.BYTES).asText());
            }
            JsonNode typeNode = node.get(Placeholders.TYPE);
            Class<?> type = typeNode == null ? null : resolve(typeNode.asText());

            if (node.has(Placeholders.ENTRIES)) {
                return readEntriesMap(node, type, declaredType, raw);
            }
            if (type == null) {
                if (Map.class.isAssignableFrom(raw) || raw == Object.class) {
                    return readStringMap(node, declaredType, raw);
                }
                type = raw;
            }
            if (Map.class.isAssignableFrom(type)) {
                return readStringMap(node, declaredType, type);
            }
            if (Throwable.class.isAssignableFrom(type)) {
                return readThrowable(node, type);
            }
            return readBean(node, type);
        }

        private Object readBean(JsonNode node, Class<?> type) throws GraphException {
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                throw new GraphException("cannot instantiate abstract type " + type.getName() + " (no @type)");
            }
            Object instance = Instantiator.newInstance(type);
            if (node.has(Placeholders.ID)) {
                byId.put(node.get(Placeholders.ID).asInt(), instance);
            }
            TypeInfo info = TypeInfo.of(type);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                if (name.startsWith("@")) {
                    continue;
                }
                TypeInfo.FieldInfo field = info.fieldsByName.get(name);
                if (field == null) {
                    continue; // written by a newer or different version of the class
                }
                if (isPlaceholder(entry.getValue())) {
                    continue; // unknown value: keep whatever the constructor initialised
                }
                Object value = read(entry.getValue(), field.field.getGenericType());
                if (value == null && field.field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.field.set(instance, value);
                } catch (Throwable t) {
                    throw new GraphException("cannot set " + type.getName() + "." + name, t);
                }
            }
            return instance;
        }

        private Object readThrowable(JsonNode node, Class<?> type) throws GraphException {
            String message = node.has("message") && !node.get("message").isNull() ? node.get("message").asText() : null;
            Throwable cause = node.has("cause") ? (Throwable) read(node.get("cause"), Throwable.class) : null;
            try {
                try {
                    return type.getConstructor(String.class, Throwable.class).newInstance(message, cause);
                } catch (NoSuchMethodException ignored) {
                    // fall through
                }
                try {
                    Throwable t = (Throwable) type.getConstructor(String.class).newInstance(message);
                    if (cause != null) {
                        t.initCause(cause);
                    }
                    return t;
                } catch (NoSuchMethodException ignored) {
                    // fall through
                }
                Throwable t = (Throwable) Instantiator.newInstance(type);
                if (cause != null) {
                    t.initCause(cause);
                }
                return t;
            } catch (GraphException e) {
                throw e;
            } catch (Throwable t) {
                throw new GraphException("cannot rebuild " + type.getName(), t);
            }
        }

        @SuppressWarnings("unchecked")
        private Object readStringMap(JsonNode node, Type declaredType, Class<?> raw) throws GraphException {
            Map<Object, Object> map = (Map<Object, Object>) newMap(raw);
            Type keyType = typeArgument(declaredType, 0);
            Type valueType = typeArgument(declaredType, 1);
            Class<?> keyClass = rawClass(keyType);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey().startsWith("@")) {
                    continue;
                }
                Object key = keyClass == Object.class || keyClass == String.class
                        ? entry.getKey()
                        : Scalars.read(com.fasterxml.jackson.databind.node.TextNode.valueOf(entry.getKey()), keyClass);
                map.put(key, read(entry.getValue(), valueType));
            }
            return map;
        }

        @SuppressWarnings("unchecked")
        private Object readEntriesMap(JsonNode node, Class<?> type, Type declaredType, Class<?> raw) throws GraphException {
            Map<Object, Object> map = (Map<Object, Object>) newMap(type != null ? type : raw);
            Type keyType = typeArgument(declaredType, 0);
            Type valueType = typeArgument(declaredType, 1);
            for (JsonNode pair : node.get(Placeholders.ENTRIES)) {
                if (pair.has(Placeholders.TRUNCATED)) {
                    break;
                }
                map.put(read(pair.get("key"), keyType), read(pair.get("value"), valueType));
            }
            return map;
        }

        @SuppressWarnings("unchecked")
        private Object readArrayNode(JsonNode node, Type declaredType, Class<?> raw) throws GraphException {
            if (raw.isArray()) {
                Class<?> component = raw.getComponentType();
                List<Object> items = new ArrayList<Object>();
                for (JsonNode element : node) {
                    if (element.isObject() && element.has(Placeholders.TRUNCATED)) {
                        break;
                    }
                    items.add(read(element, component));
                }
                Object array = Array.newInstance(component, items.size());
                for (int i = 0; i < items.size(); i++) {
                    Array.set(array, i, items.get(i));
                }
                return array;
            }
            Collection<Object> collection = (Collection<Object>) newCollection(raw);
            Type elementType = typeArgument(declaredType, 0);
            for (JsonNode element : node) {
                if (element.isObject() && element.has(Placeholders.TRUNCATED)) {
                    break;
                }
                collection.add(read(element, elementType));
            }
            return collection;
        }

        private Class<?> resolve(String name) throws GraphException {
            if (!config.typeAllowList.allows(name)) {
                throw new GraphException("type not allowed: " + name);
            }
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException e) {
                throw new GraphException("class not found through " + loader + ": " + name, e);
            }
        }
    }

    private static Collection<?> newCollection(Class<?> raw) throws GraphException {
        if (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers()) && Collection.class.isAssignableFrom(raw)) {
            return (Collection<?>) Instantiator.newInstance(raw);
        }
        if (SortedSet.class.isAssignableFrom(raw)) {
            return new TreeSet<Object>();
        }
        if (Set.class.isAssignableFrom(raw)) {
            return new LinkedHashSet<Object>();
        }
        return new ArrayList<Object>();
    }

    private static Map<?, ?> newMap(Class<?> raw) throws GraphException {
        if (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers()) && Map.class.isAssignableFrom(raw)) {
            return (Map<?, ?>) Instantiator.newInstance(raw);
        }
        if (SortedMap.class.isAssignableFrom(raw)) {
            return new TreeMap<Object, Object>();
        }
        if (ConcurrentMap.class.isAssignableFrom(raw)) {
            return new ConcurrentHashMap<Object, Object>();
        }
        return new LinkedHashMap<Object, Object>();
    }

    static boolean isPlaceholder(JsonNode node) {
        return node != null && node.isObject()
                && (node.has(Placeholders.LAZY) || node.has(Placeholders.UNSERIALIZABLE));
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawClass(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Array.newInstance(rawClass(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        }
        if (type instanceof WildcardType) {
            Type[] upper = ((WildcardType) type).getUpperBounds();
            return upper.length > 0 ? rawClass(upper[0]) : Object.class;
        }
        return Object.class;
    }

    static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (index < args.length) {
                return args[index];
            }
        }
        return Object.class;
    }
}
