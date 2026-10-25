package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import java.util.ArrayList;
import java.util.List;

/**
 * The WebLogic classes the agent advises by name: the session-bean invokers, the MDB listener
 * and the two servlet pipeline entries. One shape serves 12.2.1.4.0 and 14.1.2.0.0
 * (architecture/HOOKS.md).
 */
public final class ContainerTargets {

    public static final String SESSION_LOCAL_INVOKER = "weblogic.ejb.container.internal.SessionLocalMethodInvoker";
    public static final String SESSION_REMOTE_INVOKER = "weblogic.ejb.container.internal.SessionRemoteMethodInvoker";
    public static final String MESSAGE_LISTENER = "weblogic.ejb.container.internal.MDListener";
    public static final String FILTER_CHAIN = "weblogic.servlet.internal.FilterChainImpl";
    public static final String SERVLET_STUB = "weblogic.servlet.internal.ServletStubImpl";

    static final String[] INVOKERS = {SESSION_LOCAL_INVOKER, SESSION_REMOTE_INVOKER};
    static final String[] ALL = {SESSION_LOCAL_INVOKER, SESSION_REMOTE_INVOKER, MESSAGE_LISTENER, FILTER_CHAIN, SERVLET_STUB};

    private ContainerTargets() {
    }

    /** The target classes the system class path cannot resolve; empty on a WebLogic server. */
    public static List<String> missing() {
        List<String> missing = new ArrayList<String>();
        for (String name : ALL) {
            if (ClassLoader.getSystemResource(name.replace('.', '/') + ".class") == null) {
                missing.add(name);
            }
        }
        return missing;
    }

    static boolean isNamed(String typeName) {
        for (String name : ALL) {
            if (name.equals(typeName)) {
                return true;
            }
        }
        return JdbcTargets.isNamed(typeName);
    }
}
