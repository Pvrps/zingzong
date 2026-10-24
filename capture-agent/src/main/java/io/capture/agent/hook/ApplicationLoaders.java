package io.capture.agent.hook;

import io.capture.agent.ServerDetection;

/**
 * Whether a class belongs to a deployed application, decided by who defined it rather than
 * by its package: the server's class path carries vendor {@code @Path} / {@code @WebService}
 * classes of its own (Coherence management, consoles, REST APIs) that must not become spans.
 *
 * On WebLogic an application or module class loader is one of
 * {@code weblogic.utils.classloaders.*} ({@code GenericClassLoader} for EJB modules and EARs,
 * {@code ChangeAwareClassLoader} for web modules; docs/container-hook.md, 12.2.1.4.0 and
 * 14.1.2.0.0), never the launcher ({@code com.oracle.classloader.PolicyClassLoader
 * 'weblogic-launcher'}), the system or the bootstrap loader. On any other JVM ownership cannot
 * be told from the loader (a plain JVM, like the unit tests, runs applications on the system
 * loader), so there every loader but the bootstrap one counts and the package list decides.
 */
public final class ApplicationLoaders {

    static final String WEBLOGIC_APP_LOADERS = "weblogic.utils.classloaders.";

    private static final boolean WEBLOGIC = ServerDetection.detect() == ServerDetection.Server.WEBLOGIC;

    private ApplicationLoaders() {
    }

    public static boolean isApplication(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        if (!WEBLOGIC) {
            return true;
        }
        if (loader == ClassLoader.getSystemClassLoader()) {
            return false;
        }
        for (Class<?> c = loader.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith(WEBLOGIC_APP_LOADERS)) {
                return true;
            }
        }
        return false;
    }

}
