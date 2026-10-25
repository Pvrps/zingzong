package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.http.HttpCapture;
import ca.gc.cra.xz02.devops.wltrace.agent.http.ServletApi;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

/**
 * The facts of a request entering the servlet pipeline: the {@link HttpCapture} the tee wrappers
 * fill while the request runs. The request itself is described when the span closes; the
 * caller's principal and session come from the request, not from the server's current subject.
 */
public final class HttpFacts extends SpanFacts {

    public final HttpCapture capture;
    public final Object wrappedRequest;
    public final Object wrappedResponse;

    public HttpFacts(HttpCapture capture, Object wrappedRequest, Object wrappedResponse) {
        this.capture = capture;
        this.wrappedRequest = wrappedRequest;
        this.wrappedResponse = wrappedResponse;
    }

    @Override
    public MethodSignature signature() {
        return MethodSignature.unknown("service", "");
    }

    /** Nothing at open time: {@link ServletRecorder} writes the request once it has completed. */
    @Override
    public void describe(Span span) {
    }

    @Override
    public String principal(ServerRuntime server) {
        return ServletApi.principalOf(capture.originalRequest);
    }

    @Override
    public String httpSessionId(ServerRuntime server) {
        return ServletApi.sessionIdOf(capture.originalRequest);
    }

    @Override
    public String summary() {
        return ServletApi.string(capture.originalRequest, "getMethod") + " " + ServletApi.string(capture.originalRequest, "getRequestURI");
    }
}
