package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.http.HttpCapture;
import ca.gc.cra.xz02.devops.wltrace.agent.http.HttpInfoReader;
import ca.gc.cra.xz02.devops.wltrace.agent.http.ServletApi;
import ca.gc.cra.xz02.devops.wltrace.agent.http.TeeClassCache;
import ca.gc.cra.xz02.devops.wltrace.agent.http.TeeClasses;
import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

import java.util.List;
import java.util.Optional;

/**
 * Requests entering the servlet pipeline: an HTTP root span (or a child for a nested dispatch)
 * whose request and response are replaced by tee wrappers so both bodies are kept. Adds to the
 * base shape: {@link #enterRequest} hands the wrappers back to the advice, and the outcome is the
 * completed exchange rather than arguments and a result. A re-entry of the pipeline for a request
 * already being recorded is left alone.
 */
public final class ServletRecorder extends SpanRecorder<HttpFacts> {

    private final TeeClassCache tees;

    public ServletRecorder(RecorderContext context, TeeClassCache tees) {
        super(context);
        this.tees = tees;
    }

    @Override
    public String kind() {
        return Span.KIND_HTTP;
    }

    /**
     * Opens the span and returns {@code [wrappedRequest, wrappedResponse, token]} for the advice
     * to substitute and keep; null when nothing is recorded.
     */
    public Object[] enterRequest(Object request, Object response) {
        long token = enter(request, response, null);
        if (token == NOT_RECORDED) {
            return null;
        }
        Optional<HttpFacts> facts = nearestOpen(HttpFacts.class);
        if (!facts.isPresent()) {
            return null;
        }
        return new Object[]{facts.get().wrappedRequest, facts.get().wrappedResponse, token};
    }

    /** Closes the span opened by {@link #enterRequest}; a null exchange means nothing was opened. */
    public void exitRequest(Object[] exchange, Throwable thrown) {
        if (exchange != null) {
            exit((Long) exchange[2], null, null, thrown);
        }
    }

    /** The request and the response, as the pipeline received them; the third argument is unused. */
    @Override
    protected HttpFacts readFacts(Object request, Object response, Object unused) {
        if (!ServletApi.isHttpRequest(request) || response == null) {
            return null;
        }
        Object original = ServletApi.unwrap(request);
        Optional<HttpFacts> enclosing = nearestOpen(HttpFacts.class);
        if (enclosing.isPresent() && enclosing.get().capture.originalRequest == original) {
            return null; // the next filter in the same chain
        }
        Object wrappedRequest = request;
        Object wrappedResponse = response;
        HttpCapture capture;
        try {
            TeeClasses teeClasses = tees.forServletApiOf(request);
            capture = new HttpCapture(original, ServletApi.unwrap(response), teeClasses);
            wrappedRequest = teeClasses.wrapRequest(request, capture);
            wrappedResponse = teeClasses.wrapResponse(response, capture);
        } catch (Throwable failure) {
            log.warnOnce("tee-wrappers", "servlet tee wrappers cannot be generated, bodies will not be recorded: " + failure);
            capture = new HttpCapture(original, ServletApi.unwrap(response), null);
        }
        return new HttpFacts(capture, wrappedRequest, wrappedResponse);
    }

    /** The completed exchange: method, URI, headers, status and both bodies, plus the exception when the chain threw. */
    @Override
    protected void recordOutcome(Span span, HttpFacts facts, Object[] arguments, Object result, Throwable thrown, List<SerializationWarning> warnings) {
        span.http = HttpInfoReader.describe(facts.capture, thrown != null);
        if (thrown != null) {
            span.exception = exception(thrown, warnings);
        }
    }
}
