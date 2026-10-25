package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

/**
 * Decides whether a class belongs to a deployed application by the loader that defined it: a
 * {@code weblogic.utils.classloaders.*} loader does ({@code GenericClassLoader} for EJB modules
 * and EARs, {@code ChangeAwareClassLoader} for web modules); the launcher, the system loader
 * and the bootstrap loader do not. Vendor {@code @Path} and {@code @WebService} classes on the
 * server class path are kept out this way.
 */
public final class ApplicationClassLoaders {

    static final String WEBLOGIC_APPLICATION_LOADERS = "weblogic.utils.classloaders.";

    private ApplicationClassLoaders() {
    }

    public static boolean isApplication(ClassLoader loader) {
        if (loader == null || loader == ClassLoader.getSystemClassLoader()) {
            return false;
        }
        for (Class<?> loaderClass = loader.getClass(); loaderClass != null && loaderClass != Object.class; loaderClass = loaderClass.getSuperclass()) {
            if (loaderClass.getName().startsWith(WEBLOGIC_APPLICATION_LOADERS)) {
                return true;
            }
        }
        return false;
    }
}
