package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.HttpInfo;

import java.lang.annotation.Annotation;

/** Which resource layer a class belongs to, {@code jaxrs} or {@code jaxws}, from its annotations by name; cached per Class. */
public final class ResourceLayers {

    private static final String NO_LAYER = "";

    private final ClassValue<String> cache = new ClassValue<String>() {
        @Override
        protected String computeValue(Class<?> type) {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                String layer = layerFromAnnotations(current);
                if (layer != null) {
                    return layer;
                }
            }
            for (Class<?> implemented : type.getInterfaces()) {
                String layer = layerFromAnnotations(implemented);
                if (layer != null) {
                    return layer;
                }
            }
            return NO_LAYER;
        }
    };

    /** The layer of the class, or null when it carries neither annotation. */
    public String layerOf(Class<?> type) {
        if (type == null) {
            return null;
        }
        String layer = cache.get(type);
        return layer.isEmpty() ? null : layer;
    }

    private static String layerFromAnnotations(Class<?> type) {
        try {
            for (Annotation annotation : type.getAnnotations()) {
                String name = annotation.annotationType().getName();
                if (name.equals("javax.ws.rs.Path")) {
                    return HttpInfo.LAYER_JAXRS;
                }
                if (name.equals("javax.jws.WebService")) {
                    return HttpInfo.LAYER_JAXWS;
                }
            }
        } catch (Throwable ignored) {
            // annotation types missing at runtime
        }
        return null;
    }
}
