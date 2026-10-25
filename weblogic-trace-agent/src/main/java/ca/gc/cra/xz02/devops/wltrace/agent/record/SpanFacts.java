package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

/**
 * What one kind of span knows about its call when the call is entered, kept on the open span
 * until the exit writes it. A subclass carries the kind's facts and knows how to put them on a
 * {@link Span}; the root span's facts also say who the caller is.
 */
public abstract class SpanFacts {

    /** The method whose arguments and result the span records; {@link MethodSignature#unknown} when there is none. */
    public abstract MethodSignature signature();

    /** Writes this kind's facts onto the span: its {@code ejb}, {@code http} or {@code sql} block, layer, application and module. */
    public abstract void describe(Span span);

    /** The caller's principal for a trace this span starts; the server's current subject unless the kind knows better. */
    public String principal(ServerRuntime server) {
        return server.principal();
    }

    /** The HTTP session a trace this span starts belongs to; the thread's request unless the kind knows better. */
    public String httpSessionId(ServerRuntime server) {
        return server.httpSessionId();
    }

    /** A short label for the agent log: the class and method, or the request. */
    public abstract String label();
}
