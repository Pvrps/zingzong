package ca.gc.cra.xz02.devops.wltrace.agent.http;

import java.io.PrintWriter;

/**
 * The state of one recorded request, shared between the servlet recorder, the tee wrappers and
 * the generated streams. Fields other than the finals are written by the wrappers while the
 * request runs and read when it completes.
 */
public final class HttpCapture {

    /** The container's request object, before wrapping; its identity tells re-entries of the filter chain apart. */
    public final Object originalRequest;
    public final Object originalResponse;
    /** The generated classes for the servlet API this request's objects come from. */
    public final TeeClasses tees;
    public final BodyBuffer requestBody = new BodyBuffer(BodyBuffer.DEFAULT_CEILING);
    public final BodyBuffer responseBody = new BodyBuffer(BodyBuffer.DEFAULT_CEILING);
    /** Text written through {@code getWriter()}, kept as characters; null when the writer was never used. */
    public StringBuilder responseText;
    public String responseWriterCharset;
    /** The application's view of the streams, created once. */
    public Object requestStream;
    public Object requestReader;
    public Object responseStream;
    public PrintWriter responseWriter;
    /** {@code sendError(status, message)} as the application called it; the container writes the page itself. */
    public Integer sentErrorStatus;
    public String sentErrorMessage;

    public HttpCapture(Object originalRequest, Object originalResponse, TeeClasses tees) {
        this.originalRequest = originalRequest;
        this.originalResponse = originalResponse;
        this.tees = tees;
    }
}
