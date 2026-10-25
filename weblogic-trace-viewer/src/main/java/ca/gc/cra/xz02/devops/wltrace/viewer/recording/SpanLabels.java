package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.model.Span;

/** Short names for spans in the index: {@code OrderService.createOrder}, {@code POST /orders/api/orders}, {@code OrdersResource.create [jaxrs]}. */
public final class SpanLabels {

    private SpanLabels() {
    }

    /** The label of a span by its kind; {@code ?} for null. */
    public static String span(Span span) {
        if (span == null) {
            return "?";
        }
        if (span.ejb != null) {
            return bean(span) + "." + span.ejb.method;
        }
        if (span.http != null) {
            if (span.layer != null && span.http.resourceClass != null) {
                return simpleName(span.http.resourceClass) + "." + span.http.resourceMethod + " [" + span.layer + "]";
            }
            return span.http.method + " " + span.http.path;
        }
        return span.kind;
    }

    /** The bean name when the agent recorded it, else the class name without a trailing {@code Bean}. */
    public static String bean(Span span) {
        if (span.ejb.beanName != null) {
            return span.ejb.beanName;
        }
        String simple = simpleName(span.ejb.beanClass == null ? "?" : span.ejb.beanClass);
        return simple.endsWith("Bean") && simple.length() > "Bean".length() ? simple.substring(0, simple.length() - "Bean".length()) : simple;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
