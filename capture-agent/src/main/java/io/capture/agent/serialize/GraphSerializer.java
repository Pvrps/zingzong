package io.capture.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Type;

/**
 * Turns arbitrary object graphs into the JSON value conventions of the trace format and
 * back. An SPI so the Jackson-tree implementation can be swapped for another walker.
 *
 * Conventions produced by {@link #serialize}:
 * <ul>
 *   <li>objects: {@code {"@type": "fqcn", field: value, ...}}, with {@code "@id"} on an
 *       object that is referenced again and {@code {"@ref": id}} at every later reference;
 *       {@code "@serializable": false} when the class is not java.io.Serializable (captured
 *       anyway, but it could not cross a remote EJB boundary)</li>
 *   <li>scalars: numbers and booleans as JSON, strings, enums by name, {@code BigDecimal},
 *       {@code java.time} and {@code java.util.Date} as strings</li>
 *   <li>collections and arrays as JSON arrays; string-keyed maps as plain JSON objects,
 *       other maps as {@code {"@type": ..., "@entries": [{"key":..,"value":..}]}}</li>
 *   <li>{@code {"@lazy": true, "loaded": false}} for an un-instantiated lazy JPA value</li>
 *   <li>{@code {"@unserializable": "fqcn", "toString": "..."}} plus a warning for anything
 *       that failed, exceeded a limit, or is infrastructure (loggers, locks, executors,
 *       streams, JDBC/JPA/JMS handles, container internals; see InfrastructureTypes)</li>
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
