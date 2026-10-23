package io.capture.agent.span;

import io.capture.trace.EjbInfo;
import io.capture.trace.Span;

/** A span between its enter and exit advice. Becomes a {@link Span} on exit. */
final class OpenSpan {

    final String id;
    final String parentId;
    final int depth;
    final int order;
    final String beanClass;
    final String method;
    final long startNs;
    final long enteredAtNanos;
    final boolean selfInvocation;
    final String transaction;

    OpenSpan(String id, OpenSpan parent, int order, String beanClass, String method,
             long startNs, long enteredAtNanos, String transaction) {
        this.id = id;
        this.parentId = parent == null ? null : parent.id;
        this.depth = parent == null ? 0 : parent.depth + 1;
        this.order = order;
        this.beanClass = beanClass;
        this.method = method;
        this.startNs = startNs;
        this.enteredAtNanos = enteredAtNanos;
        this.selfInvocation = parent != null && parent.beanClass.equals(beanClass);
        this.transaction = transaction;
    }

    Span toSpan(long durationNs) {
        Span span = new Span();
        span.id = id;
        span.parentId = parentId;
        span.depth = depth;
        span.order = order;
        span.kind = Span.KIND_EJB;
        span.thread = Thread.currentThread().getName();
        span.startNs = startNs;
        span.durationNs = durationNs;
        span.selfInvocation = selfInvocation ? Boolean.TRUE : null;
        span.ejb = new EjbInfo();
        span.ejb.beanClass = beanClass;
        span.ejb.method = method;
        span.ejb.transaction = transaction;
        return span;
    }
}
