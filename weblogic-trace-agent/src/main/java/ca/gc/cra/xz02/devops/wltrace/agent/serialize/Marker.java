package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A place in a recorded value that is not the value as it was, and why. Every marker is one
 * {@code @key} with a value and, for some kinds, named details; {@link #writeTo} and
 * {@link #toNode} are the only code that knows the JSON shape. The kinds:
 * <ul>
 *   <li>{@code @type}: the runtime class of an object; on every walked object</li>
 *   <li>{@code @id} / {@code @ref}: an object referenced more than once is written in full once,
 *       carrying {@code @id}; every later reference is {@code {"@ref": id}}</li>
 *   <li>{@code @serializable: false}: walked all the same; the class is not {@code java.io.Serializable}</li>
 *   <li>{@code @lazy}: an uninstantiated lazy collection or holder, left untouched: {@code {"@lazy": true, "loaded": false}}</li>
 *   <li>{@code @handle}: a runtime handle that describes the JVM or the OS, not the payload:
 *       class, kind, identity and toString</li>
 *   <li>{@code @skipped}: on an object, the fields not written, each with its reason</li>
 *   <li>{@code @truncated}: a safety ceiling was hit; how much of what was dropped</li>
 *   <li>{@code @text} / {@code @bytes}: a string over the ceiling or a byte array, with its length</li>
 *   <li>{@code @entries}: a map whose keys are not strings, as key/value pairs</li>
 *   <li>{@code @jms}: a JMS message recorded as its API view</li>
 *   <li>{@code @unserializable}: the walk failed; class, reason, identity and toString</li>
 * </ul>
 */
public final class Marker {

    /** The marker kinds and their JSON keys. */
    public enum Kind {
        TYPE("@type"),
        ID("@id"),
        REF("@ref"),
        SERIALIZABLE("@serializable"),
        LAZY("@lazy"),
        HANDLE("@handle"),
        SKIPPED("@skipped"),
        TRUNCATED("@truncated"),
        TEXT("@text"),
        BYTES("@bytes"),
        ENTRIES("@entries"),
        JMS("@jms"),
        UNSERIALIZABLE("@unserializable");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Kind kind;
    private final JsonNode value;
    private final Map<String, JsonNode> details = new LinkedHashMap<>();

    private Marker(Kind kind, JsonNode value) {
        this.kind = kind;
        this.value = value;
    }

    public Kind kind() {
        return kind;
    }

    public static Marker type(String className) {
        return new Marker(Kind.TYPE, NODES.textNode(className));
    }

    public static Marker id(int id) {
        return new Marker(Kind.ID, NODES.numberNode(id));
    }

    public static Marker ref(int id) {
        return new Marker(Kind.REF, NODES.numberNode(id));
    }

    public static Marker notSerializable() {
        return new Marker(Kind.SERIALIZABLE, NODES.booleanNode(false));
    }

    /** An uninstantiated lazy value: {@code "loaded": false}. */
    public static Marker lazy() {
        return new Marker(Kind.LAZY, NODES.booleanNode(true)).detail("loaded", NODES.booleanNode(false));
    }

    public static Marker handle(Object value, String handleKind, String toString) {
        Marker marker = new Marker(Kind.HANDLE, NODES.textNode(value.getClass().getName()))
                .detail("kind", NODES.textNode(handleKind))
                .detail("identity", NODES.textNode(identity(value)));
        return toString == null ? marker : marker.detail("toString", NODES.textNode(toString));
    }

    /** A field left out of an object, appended to the owner's {@code @skipped} list. */
    public static Marker skipped(String field, String reason) {
        return new Marker(Kind.SKIPPED, NODES.objectNode().put("field", field).put("reason", reason));
    }

    /** A ceiling was hit: {@code what} ("elements", "entries", "chars", "bytes") and how many were dropped. */
    public static Marker truncated(String what, int dropped) {
        return new Marker(Kind.TRUNCATED, NODES.numberNode(dropped)).detail("what", NODES.textNode(what));
    }

    /** A string over the ceiling: the kept prefix, the full length and the dropped count. */
    public static Marker text(String prefix, int length, int dropped) {
        return new Marker(Kind.TEXT, NODES.textNode(prefix))
                .detail("length", NODES.numberNode(length))
                .withTruncation("chars", dropped);
    }

    /** A byte array as base64 with its length; a dropped count above zero adds the truncation. */
    public static Marker bytes(String base64, int length, int dropped) {
        return new Marker(Kind.BYTES, NODES.textNode(base64))
                .detail("length", NODES.numberNode(length))
                .withTruncation("bytes", dropped);
    }

    /** A map with non-string keys; the entries array is the marker's value and is filled by the caller. */
    public static Marker entries(ArrayNode entries) {
        return new Marker(Kind.ENTRIES, entries);
    }

    public static Marker jms(String messageKind) {
        return new Marker(Kind.JMS, NODES.textNode(messageKind));
    }

    public static Marker unserializable(Object value, String reason, String toString) {
        Marker marker = new Marker(Kind.UNSERIALIZABLE, NODES.textNode(value == null ? "null" : value.getClass().getName()))
                .detail("reason", NODES.textNode(reason));
        if (value != null) {
            marker.detail("identity", NODES.textNode(identity(value)));
        }
        return toString == null ? marker : marker.detail("toString", NODES.textNode(toString));
    }

    /** Adds this marker to an object node: its key and value, then its details; {@code @skipped} appends to the list. */
    public void writeTo(ObjectNode owner) {
        if (kind == Kind.SKIPPED) {
            ArrayNode list = owner.has(kind.key) ? (ArrayNode) owner.get(kind.key) : owner.putArray(kind.key);
            list.add(value);
            return;
        }
        owner.set(kind.key, value);
        details.forEach(owner::set);
    }

    /** This marker as a node of its own, for a value position. */
    public ObjectNode toNode() {
        ObjectNode node = NODES.objectNode();
        writeTo(node);
        return node;
    }

    private Marker detail(String name, JsonNode detailValue) {
        details.put(name, detailValue);
        return this;
    }

    private Marker withTruncation(String what, int dropped) {
        if (dropped > 0) {
            detail(Kind.TRUNCATED.key, NODES.numberNode(dropped));
            detail("what", NODES.textNode(what));
        }
        return this;
    }

    static String identity(Object value) {
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
}
