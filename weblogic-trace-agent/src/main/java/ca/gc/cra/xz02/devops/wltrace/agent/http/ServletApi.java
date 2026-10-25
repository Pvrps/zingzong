package ca.gc.cra.xz02.devops.wltrace.agent.http;

import java.lang.reflect.Method;
import java.security.Principal;

/**
 * The servlet API reached by name and reflection: the agent cannot link against
 * {@code javax.servlet}, so request and response facts are read through their public methods.
 * Every read degrades to null.
 */
public final class ServletApi {

    private static final String HTTP_REQUEST = "javax.servlet.http.HttpServletRequest";
    private static final String REQUEST_WRAPPER = "javax.servlet.ServletRequestWrapper";
    private static final int MAX_WRAPPER_DEPTH = 32;

    private ServletApi() {
    }

    public static boolean isHttpRequest(Object request) {
        return request != null && implementsByName(request.getClass(), HTTP_REQUEST);
    }

    /** The container's request behind any {@code ServletRequestWrapper} layers. */
    public static Object unwrap(Object request) {
        Object current = request;
        for (int depth = 0; depth < MAX_WRAPPER_DEPTH && current != null && implementsByName(current.getClass(), REQUEST_WRAPPER); depth++) {
            try {
                Object inner = current.getClass().getMethod("getRequest").invoke(current);
                if (inner == null) {
                    break;
                }
                current = inner;
            } catch (Throwable failure) {
                break;
            }
        }
        return current;
    }

    /** {@code getUserPrincipal().getName()}, or null. */
    public static String principalOf(Object request) {
        try {
            Object principal = request.getClass().getMethod("getUserPrincipal").invoke(request);
            return principal instanceof Principal ? ((Principal) principal).getName() : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** {@code getSession(false).getId()}, or null; never creates a session. */
    public static String sessionIdOf(Object request) {
        try {
            Method getSession = request.getClass().getMethod("getSession", boolean.class);
            Object session = getSession.invoke(request, false);
            return session == null ? null : (String) session.getClass().getMethod("getId").invoke(session);
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The string value of a public no-argument getter, or null. */
    public static String string(Object target, String getter) {
        try {
            Object value = target.getClass().getMethod(getter).invoke(target);
            return value == null ? null : value.toString();
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The integer value of a public no-argument getter, or null. */
    public static Integer integer(Object target, String getter) {
        try {
            Object value = target.getClass().getMethod(getter).invoke(target);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    static boolean implementsByName(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(name)) {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (implementsByName(implemented, name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
