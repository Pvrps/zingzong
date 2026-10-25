package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Span {

    public static final String KIND_EJB = "EJB";
    public static final String KIND_HTTP = "HTTP";
    public static final String KIND_SOAP = "SOAP";
    public static final String KIND_JMS = "JMS";
    public static final String KIND_SQL = "SQL";

    public String id;
    public String parentId;
    public int depth;
    public int order;
    /** Open enum: EJB, HTTP, SOAP, JMS, SQL. */
    public String kind;
    /** Sub-classification within a kind, e.g. "jaxrs" or "jaxws" for HTTP. */
    public String layer;

    public HttpInfo http;
    public EjbInfo ejb;
    public SqlInfo sql;

    public String app;
    public String module;
    public String thread;

    /** Captured after the call returns. */
    public List<ValueCapture> args;
    /** Captured before the call when snapshotArgs=true. */
    public List<ValueCapture> argsBefore;
    public ValueCapture result;
    public ExceptionInfo exception;
    public List<SerializationWarning> serializationWarnings;

    /** Nanoseconds relative to the trace start. */
    public long startNs;
    public long durationNs;
}
