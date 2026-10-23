package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The marker objects of the format: every place a value is not written as it was says so.
 * <ul>
 *   <li>{@code @ref} a repeated or cyclic object, see {@code @id} on its first occurrence</li>
 *   <li>{@code @lazy} an uninstantiated lazy collection or holder, left untouched</li>
 *   <li>{@code @handle} a non-walkable runtime handle: type, kind, identity, toString</li>
 *   <li>{@code @skipped} on an object: fields not written, each with its reason</li>
 *   <li>{@code @truncated} a safety ceiling was hit: what and how much was dropped</li>
 *   <li>{@code @unserializable} the walk failed: type, reason, toString where safe</li>
 *   <li>{@code @serializable: false} walked normally, but cannot cross a remote boundary</li>
 * </ul>
 */
final class Placeholders {

    static final String TYPE = "@type";
    static final String ID = "@id";
    static final String SERIALIZABLE = "@serializable";
    static final String REF = "@ref";
    static final String LAZY = "@lazy";
    static final String LOADED = "loaded";
    static final String HANDLE = "@handle";
    static final String KIND = "kind";
    static final String IDENTITY = "identity";
    static final String SKIPPED = "@skipped";
    static final String UNSERIALIZABLE = "@unserializable";
    static final String REASON = "reason";
    static final String TO_STRING = "toString";
    static final String ENTRIES = "@entries";
    static final String BYTES = "@bytes";
    static final String TEXT = "@text";
    static final String TRUNCATED = "@truncated";
    static final String LENGTH = "length";
    static final String DROPPED = "dropped";
    static final String WHAT = "what";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Placeholders() {
    }

    static ObjectNode object() {
        return NODES.objectNode();
    }

    static ObjectNode lazy(boolean loaded) {
        return object().put(LAZY, true).put(LOADED, loaded);
    }

    static ObjectNode ref(int id) {
        return object().put(REF, id);
    }

    static ObjectNode handle(Object value, String kind, String toString) {
        ObjectNode node = object()
                .put(HANDLE, value.getClass().getName())
                .put(KIND, kind)
                .put(IDENTITY, identity(value));
        if (toString != null) {
            node.put(TO_STRING, toString);
        }
        return node;
    }

    static ObjectNode unserializable(Object value, String reason, String toString) {
        ObjectNode node = object()
                .put(UNSERIALIZABLE, value == null ? "null" : value.getClass().getName())
                .put(REASON, reason);
        if (value != null) {
            node.put(IDENTITY, identity(value));
        }
        if (toString != null) {
            node.put(TO_STRING, toString);
        }
        return node;
    }

    /** A ceiling was hit inside a container: how many elements were left out. */
    static ObjectNode truncatedElements(String what, int dropped) {
        return object().put(TRUNCATED, dropped).put(WHAT, what);
    }

    static ObjectNode bytes(String base64, int length, int dropped) {
        ObjectNode node = object().put(BYTES, base64).put(LENGTH, length);
        if (dropped > 0) {
            node.put(TRUNCATED, dropped).put(WHAT, "bytes");
        }
        return node;
    }

    static ObjectNode text(String prefix, int length, int dropped) {
        return object().put(TEXT, prefix).put(LENGTH, length).put(TRUNCATED, dropped).put(WHAT, "chars");
    }

    static void skipped(ObjectNode owner, String field, String reason) {
        ArrayNode list = owner.has(SKIPPED) ? (ArrayNode) owner.get(SKIPPED) : owner.putArray(SKIPPED);
        list.addObject().put("field", field).put(REASON, reason);
    }

    static String identity(Object value) {
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
}
