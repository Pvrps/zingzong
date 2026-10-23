package io.capture.agent;

/**
 * Which application server the agent was started in. Decides the hook: the container hook on
 * WebLogic when its invoker classes are present, the portable annotation hook everywhere else.
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

    /** The hook to use: the override if given, else by server. */
    public static String chooseHook(String override, Server server, boolean containerHookAvailable) {
        if (override != null) {
            return override;
        }
        if (server == Server.WEBLOGIC && containerHookAvailable) {
            return AgentConfig.HOOK_CONTAINER;
        }
        return AgentConfig.HOOK_ANNOTATION;
    }
}
