package io.capture.agent.http;

import java.io.PrintWriter;

/** Per-request state shared between the servlet advice, the tee wrappers and the recorder. */
public final class HttpCapture {

    /** The container's request object, before wrapping; identity tells re-entries of the filter chain apart. */
    public final Object originalRequest;
    public final Object originalResponse;
    public final BodyBuffer requestBody = new BodyBuffer(BodyBuffer.DEFAULT_CEILING);
    public final BodyBuffer responseBody = new BodyBuffer(BodyBuffer.DEFAULT_CEILING);
    /** Text written through getWriter(), kept as characters; null when the writer was never used. */
    public StringBuilder responseText;
    public String responseWriterCharset;
    public Object wrappedRequest;
    public Object wrappedResponse;
    /** The application's view of the streams, created once. */
    public Object requestStream;
    public Object requestReader;
    public Object responseStream;
    public PrintWriter responseWriter;
    /** sendError(status, message) as the application called it; the container writes the page itself. */
    public Integer sentErrorStatus;
    public String sentErrorMessage;

    public HttpCapture(Object originalRequest, Object originalResponse) {
        this.originalRequest = originalRequest;
        this.originalResponse = originalResponse;
    }
}
