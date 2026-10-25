package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

/**
 * Decides whether a class belongs to a deployed application by the loader that defined it:
 * a {@code weblogic.utils.classloaders.*} loader does ({@code GenericClassLoader} for EJB
 * modules and EARs, {@code ChangeAwareClassLoader} for web modules); the launcher, the system
 * loader and the bootstrap loader do not. Vendor {@code @Path} and {@code @WebService} classes
 * on the server class path are kept out this way.
 */
public final class ApplicationLoaders {

    static final String WEBLOGIC_APP_LOADERS = "weblogic.utils.classloaders.";

    private ApplicationLoaders() {
    }

    public static boolean isApplication(ClassLoader loader) {
        if (loader == null || loader == ClassLoader.getSystemClassLoader()) {
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
