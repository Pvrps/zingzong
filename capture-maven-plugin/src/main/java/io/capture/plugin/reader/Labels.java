package io.capture.plugin.reader;

import io.capture.trace.Span;

/** Human-readable names for spans, used by the CLI and the index. */
public final class Labels {

    private Labels() {
    }

    /** "OrderService.createOrder" for an EJB span, "POST /orders/api/orders" for HTTP. */
    public static String span(Span span) {
        if (span == null) {
            return "?";
        }
        if (span.ejb != null) {
            return bean(span) + "." + span.ejb.method;
        }
        if (span.http != null) {
            if (span.layer != null && span.http.resourceClass != null) {
                String cls = span.http.resourceClass;
                int dot = cls.lastIndexOf('.');
                return (dot < 0 ? cls : cls.substring(dot + 1)) + "." + span.http.resourceMethod + " [" + span.layer + "]";
            }
            return span.http.method + " " + span.http.path;
        }
        return span.kind;
    }

    /** The bean name when the agent knows it (Phase 6), else the class name without a trailing "Bean". */
    public static String bean(Span span) {
        if (span.ejb.beanName != null) {
            return span.ejb.beanName;
        }
        String name = span.ejb.beanClass == null ? "?" : span.ejb.beanClass;
        int dot = name.lastIndexOf('.');
        String simple = dot < 0 ? name : name.substring(dot + 1);
        return simple.endsWith("Bean") && simple.length() > 4 ? simple.substring(0, simple.length() - 4) : simple;
    }
}
