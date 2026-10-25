package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** The exception a call threw: class, message and the serialized object. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExceptionInfo {

    public String className;
    public String message;
    /** The serialized exception object: class, message, top stack frames, cause chain. */
    public JsonNode value;
}
