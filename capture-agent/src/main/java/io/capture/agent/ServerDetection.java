package io.capture.agent;

/**
 * Which application server the agent was started in. Decides the hook: the container hook on
 * WebLogic when its invoker classes are present, the portable annotation hook everywhere else.
 * There is no override; tests exercise the annotation hook by constructing it directly.
 */
public final class ServerDetection {

    public enum Server { WEBLOGIC, UNKNOWN }

    private ServerDetection() {
    }

    public static Server detect() {
        if (System.getProperty("weblogic.home") != null || System.getProperty("weblogic.Name") != null) {
            return Server.WEBLOGIC;
        }
        try {
            if (ClassLoader.getSystemResource("weblogic/Server.class") != null) {
                return Server.WEBLOGIC;
            }
        } catch (Throwable ignored) {
            // treat as unknown
        }
        return Server.UNKNOWN;
    }

    public static boolean useContainerHook(Server server, boolean containerTargetsPresent) {
        return server == Server.WEBLOGIC && containerTargetsPresent;
    }
}
