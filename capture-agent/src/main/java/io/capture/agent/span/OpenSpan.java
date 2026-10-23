package io.capture.agent.span;

import io.capture.agent.meta.BeanMeta;
import io.capture.trace.EjbInfo;
import io.capture.trace.HttpInfo;
import io.capture.trace.Span;

import java.util.ArrayList;
import java.util.List;

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
    /**
     * A bean method reached from another method of the same bean without passing through the
     * container (no business interface in between). Only the annotation hook can see these;
     * the container hook never produces such a span.
     */
    final boolean selfInvocation;
    /** The servlet capture state for a servlet span; null otherwise. */
    final Object http;
    /** The container's invocation objects for a container-hook span; null otherwise. */
    final Object container;
    /** EJB metadata, null for non-EJB spans. */
    final BeanMeta meta;
    final String interfaceName;
    final String invocation;
    final String transactionAttribute;

    OpenSpan(String id, OpenSpan parent, int order, String kind, String layer, String beanClass, String method,
             long startNs, long enteredAtNanos, Object http, Object container,
             BeanMeta meta, String interfaceName, String invocation, String transactionAttribute) {
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
        this.selfInvocation = Span.KIND_EJB.equals(kind) && parent != null && beanClass != null
                && beanClass.equals(parent.beanClass) && interfaceName == null;
        this.http = http;
        this.container = container;
        this.meta = meta;
        this.interfaceName = interfaceName;
        this.invocation = invocation;
        this.transactionAttribute = transactionAttribute;
    }

    boolean isServlet() {
        return http != null;
    }

    /**
     * The bean's JNDI names with the portable name of the invoked interface first, so that
     * {@code jndiNames[0]} is what a client of this span's view would look up; the rest sorted.
     * A self-invocation (no interface) lists them sorted only.
     */
    private List<String> jndiNames() {
        List<String> names = new ArrayList<String>(meta.jndiNames);
        String invoked = meta.globalName(interfaceName);
        if (invoked != null) {
            names.remove(invoked);
            names.add(0, invoked);
        }
        return names.isEmpty() ? null : names;
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
            span.ejb.interfaceName = interfaceName;
            span.ejb.invocation = invocation;
            span.ejb.transactionAttribute = transactionAttribute;
            if (meta != null) {
                span.app = meta.app;
                span.module = meta.module;
                span.ejb.app = meta.app;
                span.ejb.module = meta.module;
                span.ejb.beanName = meta.ejbName;
                span.ejb.jndiNames = jndiNames();
            }
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
