package io.capture.agent.meta;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For the annotation hook, which runs inside the bean method and cannot see the container's
 * invocation: the business interface a call came in through is read from the stack. WebLogic
 * invokes a bean through a generated client object named
 * {@code <package>.<EjbName>_<hash>_<InterfaceSimpleName>Impl}, whose frame sits directly
 * above the bean method (interceptor and proxy frames in between). A bean method reached
 * from another method of the same bean without such a frame is a self-invocation: null.
 */
public final class InvokedInterface {

    private static final Pattern GENERATED_IMPL = Pattern.compile("(.+)\\.([^.]+)_[0-9a-z]+_([^.]+)Impl$");
    private static final String[] PASS_THROUGH = {
            "jdk.proxy", "com.sun.proxy", "java.lang.reflect.", "jdk.internal.reflect.", "sun.reflect.",
            "com.bea.core.repackaged.springframework.", "com.oracle.pitchfork.", "org.jboss.weld.",
            "io.capture.", "javax.interceptor.", "org.glassfish.",
    };

    private InvokedInterface() {
    }

    /**
     * The fully qualified business interface, or null for a self-invocation. {@code candidates}
     * maps interface name → view, from {@link BeanMeta#interfaces}.
     */
    public static String fromStack(String beanClassName, String ejbName, Map<String, String> candidates) {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        boolean inBean = false;
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.equals(beanClassName)) {
                if (inBean) {
                    // The bean's own frame directly above the method: a direct call from a
                    // sibling method, never through the container. The generated impl frame
                    // further up belongs to the outer call.
                    return null;
                }
                inBean = true;
                continue;
            }
            if (!inBean || passThrough(cls)) {
                continue;
            }
            Matcher m = GENERATED_IMPL.matcher(cls);
            if (m.matches() && m.group(2).equals(ejbName)) {
                return resolve(m.group(3), candidates);
            }
            // A frame of another class between the bean and the container: the bean was called
            // directly (self-invocation through a helper) or by something that is not the container.
            if (cls.startsWith("weblogic.ejb.")) {
                continue;
            }
            return null;
        }
        return null;
    }

    static String resolve(String simpleName, Map<String, String> candidates) {
        for (String name : candidates.keySet()) {
            int dot = name.lastIndexOf('.');
            if (name.substring(dot + 1).equals(simpleName)) {
                return name;
            }
        }
        return null;
    }

    private static boolean passThrough(String cls) {
        for (String prefix : PASS_THROUGH) {
            if (cls.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
