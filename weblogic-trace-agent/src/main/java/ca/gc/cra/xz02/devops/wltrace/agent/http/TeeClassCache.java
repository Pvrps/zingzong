package ca.gc.cra.xz02.devops.wltrace.agent.http;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;

/**
 * One {@link TeeClasses} per servlet API, generated on first use and cached on the API's
 * {@code HttpServletRequestWrapper} Class object, so the generated classes are released with the
 * loader that defined the API.
 */
public final class TeeClassCache {

    private final AgentLog log;
    private final ClassValue<TeeClasses> cache = new ClassValue<TeeClasses>() {
        @Override
        protected TeeClasses computeValue(Class<?> requestWrapperClass) {
            return generate(requestWrapperClass.getClassLoader());
        }
    };

    public TeeClassCache(AgentLog log) {
        this.log = log;
    }

    /** The generated classes for the servlet API visible from the object's class. */
    public TeeClasses forServletApiOf(Object servletObject) throws ClassNotFoundException {
        ClassLoader loader = servletObject.getClass().getClassLoader();
        Class<?> wrapper = Class.forName(TeeClasses.REQUEST_WRAPPER, false, loader == null ? ClassLoader.getSystemClassLoader() : loader);
        return cache.get(wrapper);
    }

    private TeeClasses generate(ClassLoader servletLoader) {
        long start = System.nanoTime();
        try {
            TeeClasses generated = TeeClasses.generate(servletLoader);
            log.info("generated servlet tee classes for " + describe(servletLoader) + " in " + (System.nanoTime() - start) / 1000000L + "ms");
            return generated;
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot generate servlet tee classes for " + describe(servletLoader), failure);
        }
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        String text = loader.toString();
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }
}
