package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExceptionInfo {

    public String className;
    public String message;
    /** The serialized exception object: class, message, top stack frames, cause chain. */
    public JsonNode value;
}
