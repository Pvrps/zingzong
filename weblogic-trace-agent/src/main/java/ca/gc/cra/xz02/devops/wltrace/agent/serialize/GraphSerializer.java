package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns an object graph into the JSON value conventions of the trace format, raw: every instance
 * field of every object, no getters. The conventions:
 * <ul>
 *   <li>objects: {@code {"@type": "fqcn", field: value, ...}}, with {@code "@id"} on an object
 *       that is referenced again and {@code {"@ref": id}} at every later reference;
 *       {@code "@serializable": false} when the class is not java.io.Serializable;
 *       {@code "@skipped": [{field, reason}]} listing any field not written</li>
 *   <li>scalars: numbers and booleans as JSON, strings, enums by name, JDK value types by their
 *       canonical text ({@link Scalars})</li>
 *   <li>collections and arrays as JSON arrays; string-keyed maps as plain JSON objects, other
 *       maps as {@code {"@type": ..., "@entries": [{"key":..,"value":..}]}}; {@code Optional}
 *       as {@code {"@type": "java.util.Optional", "present": .., "value": ..}}</li>
 *   <li>{@code {"@lazy": true, "loaded": false}} for an un-instantiated lazy JPA value</li>
 *   <li>{@code {"@handle": "fqcn", "kind": .., "identity": .., "toString": ..}} for a
 *       non-walkable runtime handle ({@link InfrastructureTypes})</li>
 *   <li>{@code {"@truncated": n, "what": ..}} wherever a safety ceiling was hit; strings over the
 *       ceiling become {@code {"@text", "length", "@truncated"}}, byte arrays carry the same on
 *       their {@code @bytes} node</li>
 *   <li>{@code {"@unserializable": "fqcn", "reason": .., "identity": .., "toString": ..}} plus a
 *       warning when the walk itself failed</li>
 * </ul>
 */
public interface GraphSerializer {

    /** Never throws; failures degrade to placeholders and warnings on the context. */
    JsonNode serialize(Object value, Class<?> declaredType, SerializationContext context);
}
