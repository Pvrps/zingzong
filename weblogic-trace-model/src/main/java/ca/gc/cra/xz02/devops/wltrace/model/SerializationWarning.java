package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A place in a captured value where the serializer could not write the value as it was, and why. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SerializationWarning {

    /** JSON path within the span, e.g. "$.args[0].lines[0].product.supplier". */
    public String path;
    public String reason;
    public String typeName;

    public SerializationWarning() {
    }

    public SerializationWarning(String path, String reason, String typeName) {
        this.path = path;
        this.reason = reason;
        this.typeName = typeName;
    }
}
