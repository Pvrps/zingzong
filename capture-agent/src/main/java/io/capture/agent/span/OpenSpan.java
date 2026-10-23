package io.capture.agent.span;

import io.capture.trace.EjbInfo;
import io.capture.trace.HttpInfo;
import io.capture.trace.Span;

/** A span between its enter and exit advice. Becomes a {@link Span} on exit. */
final class OpenSpan {

    final String id;
    final String parentId;
    final int depth;
    final int order;
    final String kind;
    /** HTTP sub-layer: null for a servlet root, "jaxrs" / "jaxws" for the resource layer. */
    final String layer;
    /** Bean class for EJB spans, resource class for jaxrs/jaxws, null for servlet roots. */
    final String beanClass;
    final String method;
    final long startNs;
    final long enteredAtNanos;
    final boolean selfInvocation;
    final String transaction;
    /** The servlet capture state for a servlet span; null otherwise. */
    final Object http;

    OpenSpan(String id, OpenSpan parent, int order, String kind, String layer, String beanClass, String method,
             long startNs, long enteredAtNanos, String transaction, Object http) {
        this.id = id;
        this.parentId = parent == null ? null : parent.id;
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.order = order;
        this.kind = kind;
        this.layer = layer;
        this.beanClass = beanClass;
        this.method = method;
        this.startNs = startNs;
        this.enteredAtNanos = enteredAtNanos;
        this.selfInvocation = parent != null && beanClass != null && beanClass.equals(parent.beanClass);
        this.transaction = transaction;
        this.http = http;
    }

    boolean isServlet() {
        return http != null;
    }

    Span toSpan(long durationNs) {
        Span span = new Span();
        span.id = id;
        span.parentId = parentId;
        span.depth = depth;
        span.order = order;
        span.kind = kind;
        span.layer = layer;
        span.thread = Thread.currentThread().getName();
        span.startNs = startNs;
        span.durationNs = durationNs;
        span.selfInvocation = selfInvocation ? Boolean.TRUE : null;
        if (Span.KIND_EJB.equals(kind)) {
            span.ejb = new EjbInfo();
            span.ejb.beanClass = beanClass;
            span.ejb.method = method;
            span.ejb.transaction = transaction;
        } else {
            span.http = new HttpInfo();
            if (layer != null) {
                span.http.resourceClass = beanClass;
                span.http.resourceMethod = method;
            }
        }
        return span;
    }
}
