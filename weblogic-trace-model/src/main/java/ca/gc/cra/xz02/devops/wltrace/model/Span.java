package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One call in a trace: an HTTP request or resource method, an EJB invocation or an SQL statement,
 * with its position in the tree, its timing and what crossed its boundary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Span {

    public static final String KIND_EJB = "EJB";
    public static final String KIND_HTTP = "HTTP";
    public static final String KIND_SQL = "SQL";

    public String id;
    public String parentId;
    public int depth;
    public int order;
    /** EJB, HTTP or SQL. */
    public String kind;
    /** Sub-classification within a kind, e.g. "jaxrs" or "jaxws" for HTTP. */
    public String layer;

    public HttpInfo http;
    public EjbInfo ejb;
    public SqlInfo sql;

    public String app;
    public String module;
    public String thread;

    /** Captured after the call returns, so what the callee saw and did to them. */
    public List<ValueCapture> args;
    public ValueCapture result;
    public ExceptionInfo exception;
    public List<SerializationWarning> serializationWarnings;

    /** Nanoseconds relative to the trace start. */
    public long startNs;
    public long durationNs;
}
