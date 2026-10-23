package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The marker objects of the format. Field names are the contract; see {@link GraphSerializer}. */
final class Placeholders {

    static final String TYPE = "@type";
    static final String ID = "@id";
    /** Present (false) on a captured object that is not java.io.Serializable: data kept, not T3-transportable. */
    static final String SERIALIZABLE = "@serializable";
    static final String REF = "@ref";
    static final String LAZY = "@lazy";
    static final String LOADED = "loaded";
    static final String UNSERIALIZABLE = "@unserializable";
    static final String TO_STRING = "toString";
    static final String ENTRIES = "@entries";
    static final String BYTES = "@bytes";
    static final String TRUNCATED = "@truncated";
    static final String LENGTH = "length";

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

    static ObjectNode unserializable(Object value, String toString) {
        ObjectNode node = object().put(UNSERIALIZABLE, value == null ? "null" : value.getClass().getName());
        if (toString != null) {
            node.put(TO_STRING, toString);
        }
        return node;
    }

    /** Marks the end of a capped collection: how many elements were left out. */
    static ObjectNode truncated(int omitted) {
        return object().put(TRUNCATED, omitted);
    }

    static ObjectNode bytes(String base64, int length, boolean truncated) {
        ObjectNode node = object().put(BYTES, base64).put(LENGTH, length);
        if (truncated) {
            node.put(TRUNCATED, true);
        }
        return node;
    }
}
