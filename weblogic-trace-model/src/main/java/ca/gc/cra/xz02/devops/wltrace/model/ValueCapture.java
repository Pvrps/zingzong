package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A serialized argument or result. {@code value} follows the graph conventions: objects carry
 * "@type", repeated references become {"@ref": id} with the first occurrence carrying "@id",
 * unserializable values become {"@unserializable": class, "toString": ...}, lazy JPA
 * collections become {"@lazy": true, "loaded": false}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValueCapture {

    public String declaredType;
    public JsonNode value;
}
