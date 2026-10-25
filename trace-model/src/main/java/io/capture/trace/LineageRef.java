package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One end of a lineage entry: a path inside a span of a trace. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageRef {

    /** Trace id; absent on "to" when it refers to the enclosing trace. */
    public String trace;
    public String span;
    /** Path within the span, e.g. "args[0].orderId" or "result.id". */
    public String path;

    public LineageRef() {
    }

    public LineageRef(String trace, String span, String path) {
        this.trace = trace;
        this.span = span;
        this.path = path;
    }
}
