package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.HttpInfo;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

/** The facts of a JAX-RS resource or JAX-WS endpoint method: the class, the method and the layer ({@code jaxrs} or {@code jaxws}). */
public final class ResourceFacts extends SpanFacts {

    private final String resourceClassName;
    private final MethodSignature signature;
    private final String layer;

    public ResourceFacts(String resourceClassName, MethodSignature signature, String layer) {
        this.resourceClassName = resourceClassName;
        this.signature = signature;
        this.layer = layer;
    }

    @Override
    public MethodSignature signature() {
        return signature;
    }

    @Override
    public void describe(Span span) {
        span.layer = layer;
        HttpInfo http = new HttpInfo();
        http.resourceClass = resourceClassName;
        http.resourceMethod = signature.name;
        http.resourceSignature = signature.text;
        span.http = http;
    }

    @Override
    public String summary() {
        return resourceClassName.substring(resourceClassName.lastIndexOf('.') + 1) + "." + signature.name;
    }
}
