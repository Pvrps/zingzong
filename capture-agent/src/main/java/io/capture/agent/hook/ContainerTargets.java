package io.capture.agent.hook;

/**
 * The EJB container choke points the container hook advises, per WebLogic generation,
 * resolved once at startup: the first entry whose classes exist on the system class path
 * is the one. Each entry is verified by thread dump and recorded in docs/container-hook.md
 * with version and patch level; a WebLogic whose invokers match no entry gets the
 * annotation hook, and {@link FallbackHook} still checks that the advice landed.
 */
public final class ContainerTargets {

    /** One verified shape of the container: the static {@code invoke(Invokable, BaseXObject, InvocationWrapper, Object[], int)} pair. */
    public static final class Target {
        public final String versions;
        public final String[] invokers;

        Target(String versions, String... invokers) {
            this.versions = versions;
            this.invokers = invokers;
        }
    }

    /** Newest first. 12.2.1.4.0 and 14.1.2.0.0 share one shape (same classes, methods, even line numbers). */
    static final Target[] KNOWN = {
            new Target("WebLogic 12.2.1.4.0 and 14.1.2.0.0",
                    "weblogic.ejb.container.internal.SessionLocalMethodInvoker",
                    "weblogic.ejb.container.internal.SessionRemoteMethodInvoker"),
    };

    private ContainerTargets() {
    }

    /** The target whose classes this JVM can resolve, or null (then: annotation hook). */
    public static Target resolve() {
        for (Target target : KNOWN) {
            boolean all = true;
            for (String name : target.invokers) {
                all &= resolvable(name);
            }
            if (all) {
                return target;
            }
        }
        return null;
    }

    static boolean isNamedInvoker(String typeName) {
        for (Target target : KNOWN) {
            for (String name : target.invokers) {
                if (name.equals(typeName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean resolvable(String name) {
        try {
            return ClassLoader.getSystemResource(name.replace('.', '/') + ".class") != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
