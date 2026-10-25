package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.HttpInfo;

import java.lang.annotation.Annotation;

/** Which resource layer a class belongs to, from its annotations by name; cached per Class. */
final class ResourceLayers {

    private static final ClassValue<String> CACHE = new ClassValue<String>() {
        @Override
        protected String computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                String layer = layerFromAnnotations(c);
                if (layer != null) {
                    return layer;
                }
            }
            for (Class<?> i : type.getInterfaces()) {
                String layer = layerFromAnnotations(i);
                if (layer != null) {
                    return layer;
                }
            }
            return "";
        }
    };

    private ResourceLayers() {
    }

    static String layerOf(Class<?> type) {
        if (type == null) {
            return null;
        }
        String layer = CACHE.get(type);
        return layer.isEmpty() ? null : layer;
    }

    private static String layerFromAnnotations(Class<?> c) {
        try {
            for (Annotation a : c.getAnnotations()) {
                String name = a.annotationType().getName();
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
