package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.Span;

/**
 * JAX-RS resource and JAX-WS endpoint methods: a child HTTP span with layer {@code jaxrs} or
 * {@code jaxws}, arguments and result recorded like an EJB call. The method is resolved from the
 * origin string the advice passes, {@code <class>|<method>|<descriptor>}, against the user's
 * class behind the instance. Adds nothing to the base shape.
 */
public final class ResourceRecorder extends SpanRecorder<ResourceFacts> {

    private final MethodResolver methods;
    private final ResourceLayers layers;

    public ResourceRecorder(RecorderContext context, MethodResolver methods, ResourceLayers layers) {
        super(context);
        this.methods = methods;
        this.layers = layers;
    }

    @Override
    public String kind() {
        return Span.KIND_HTTP;
    }

    /** The resource instance and the advice origin {@code <class>|<method>|<descriptor>}; the third argument is unused. */
    @Override
    protected ResourceFacts readFacts(Object resource, Object origin, Object unused) {
        String[] parts = String.valueOf(origin).split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        String resourceClassName = parts[0];
        Class<?> resourceClass = MethodResolver.beanClassOf(resource, resourceClassName);
        MethodSignature signature = methods.resolve(resourceClass, parts[1], parts[2]);
        return new ResourceFacts(resourceClassName, signature, layers.layerOf(resource == null ? null : resource.getClass()));
    }
}
