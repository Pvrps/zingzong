package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Type;

/**
 * Turns arbitrary object graphs into the JSON value conventions of the trace format and
 * back. An SPI so the Jackson-tree implementation can be swapped for another walker.
 *
 * Capture is raw: what crossed the boundary, as it was, every instance field of every object
 * walked without getters. Conventions produced by {@link #serialize}:
 * <ul>
 *   <li>objects: {@code {"@type": "fqcn", field: value, ...}}, with {@code "@id"} on an
 *       object that is referenced again and {@code {"@ref": id}} at every later reference;
 *       {@code "@serializable": false} when the class is not java.io.Serializable (walked
 *       anyway, but it could not cross a remote EJB boundary); {@code "@skipped": [{field,
 *       reason}]} listing any field not written (compiler- or weaver-injected, inaccessible)</li>
 *   <li>scalars: numbers and booleans as JSON, strings, enums by name, {@code BigDecimal},
 *       {@code java.time}, {@code java.util.Date} and other JDK value types by their
 *       canonical text (see Scalars)</li>
 *   <li>collections and arrays as JSON arrays; string-keyed maps as plain JSON objects,
 *       other maps as {@code {"@type": ..., "@entries": [{"key":..,"value":..}]}};
 *       {@code Optional} as {@code {"@type": "java.util.Optional", "present": .., "value": ..}}</li>
 *   <li>{@code {"@lazy": true, "loaded": false}} for an un-instantiated lazy JPA value,
 *       because loading it would change the call</li>
 *   <li>{@code {"@handle": "fqcn", "kind": .., "identity": .., "toString": ..}} for a
 *       non-walkable runtime handle (threads, class loaders, streams, sockets, JDBC
 *       connections, locks, executors; see InfrastructureTypes), because it is not payload</li>
 *   <li>{@code {"@truncated": n, "what": ..}} wherever a safety ceiling was hit, with the
 *       count of what was dropped; strings over the ceiling become {@code {"@text", "length",
 *       "@truncated"}}, byte arrays carry the same on their {@code @bytes} node</li>
 *   <li>{@code {"@unserializable": "fqcn", "reason": .., "identity": .., "toString": ..}}
 *       plus a warning when the walk itself failed</li>
 * </ul>
 */
public interface GraphSerializer {

    /** Never throws; failures degrade to placeholders and warnings on the context. */
    JsonNode serialize(Object value, Class<?> declaredType, SerializationContext context);

    /**
     * Rebuilds a value. Classes named by {@code "@type"} are resolved through {@code loader}
     * and must pass the configured type allow-list. {@code declaredType} guides scalars,
     * collections and maps that carry no type information of their own.
     */
    Object deserialize(JsonNode node, Type declaredType, ClassLoader loader) throws GraphException;
}
