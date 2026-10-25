package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns an object graph into the JSON of the trace format, raw: every instance field of every
 * object, no getters, nothing loaded that was not loaded. Objects become {@code {"@type": ...,
 * field: value}}, scalars their lossless JSON form ({@link Scalars}), collections and arrays
 * JSON arrays, string-keyed maps JSON objects; everything else that is not written as it was
 * carries a {@link Marker}.
 */
public interface GraphSerializer {

    /** Never throws: a failure becomes an {@code @unserializable} marker and a warning on the context. */
    JsonNode serialize(Object value, Class<?> declaredType, SerializationContext context);
}
