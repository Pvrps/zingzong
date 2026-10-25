package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Facts about the server and the current thread, read reflectively, never linked: the server
 * name, the current WebLogic principal and the id of the HTTP session the thread's request
 * belongs to. Every lookup degrades to null; a failure is logged once and the lookup is not
 * attempted again.
 */
public final class ServerRuntime {

    private static final String SECURITY_CLASS = "weblogic.security.Security";
    private static final String WLS_USER_PRINCIPAL = "weblogic.security.principal.WLSUserImpl";
    private static final String EXECUTE_THREAD = "weblogic.work.ExecuteThread";
    private static final String REQUEST_EXECUTOR_SUFFIX = "WlsRequestExecutor";

    private final AgentLog log;
    private final AtomicBoolean principalUnavailable = new AtomicBoolean();
    private final AtomicBoolean sessionUnavailable = new AtomicBoolean();
    private volatile Method getCurrentSubject;

    public ServerRuntime(AgentLog log) {
        this.log = log;
    }

    /** The {@code weblogic.Name} system property, or null outside a server. */
    public String serverName() {
        return System.getProperty("weblogic.Name");
    }

    /** User name of the current WebLogic subject: the {@code WLSUserImpl} principal, else the first principal, else null. */
    public String principal() {
        if (principalUnavailable.get()) {
            return null;
        }
        try {
            Method method = getCurrentSubject;
            if (method == null) {
                Class<?> security = Class.forName(SECURITY_CLASS, true, contextLoader());
                method = security.getMethod("getCurrentSubject");
                getCurrentSubject = method;
            }
            Object subject = method.invoke(null);
            if (subject == null) {
                return null;
            }
            Set<?> principals = (Set<?>) subject.getClass().getMethod("getPrincipals").invoke(subject);
            String first = null;
            for (Object principal : principals) {
                if (!(principal instanceof Principal)) {
                    continue;
                }
                String name = ((Principal) principal).getName();
                if (principal.getClass().getName().equals(WLS_USER_PRINCIPAL)) {
                    return name;
                }
                if (first == null) {
                    first = name;
                }
            }
            return first;
        } catch (Throwable failure) {
            principalUnavailable.set(true);
            log.warnOnce("principal", "principal unavailable (" + SECURITY_CLASS + ".getCurrentSubject): " + failure + "; recording null from now on");
            return null;
        }
    }

    /**
     * Id of the HTTP session the current thread's request belongs to, or null (no request, no
     * session yet). Used for roots no servlet span opened, such as a T3 call. Never creates a
     * session. Path: {@code ExecuteThread.getCurrentWork()} to {@code WlsRequestExecutor.connectionHandler}
     * to {@code AbstractHttpConnectionHandler.getServletRequest()} to {@code HttpServletRequest.getSession(false)}.
     */
    public String httpSessionId() {
        if (sessionUnavailable.get()) {
            return null;
        }
        try {
            Thread thread = Thread.currentThread();
            if (!thread.getClass().getName().equals(EXECUTE_THREAD)) {
                return null;
            }
            Object work = thread.getClass().getMethod("getCurrentWork").invoke(thread);
            if (work == null || !work.getClass().getName().endsWith(REQUEST_EXECUTOR_SUFFIX)) {
                return null;
            }
            Field handlerField = work.getClass().getDeclaredField("connectionHandler");
            handlerField.setAccessible(true);
            Object handler = handlerField.get(work);
            if (handler == null) {
                return null;
            }
            Object request = accessible(handler.getClass().getMethod("getServletRequest")).invoke(handler);
            if (request == null) {
                return null;
            }
            Object session = accessible(request.getClass().getMethod("getSession", boolean.class)).invoke(request, false);
            if (session == null) {
                return null;
            }
            return (String) accessible(session.getClass().getMethod("getId")).invoke(session);
        } catch (InvocationTargetException objection) {
            // The server objected to this particular request (recycled, upgraded, ...): just no id.
            return null;
        } catch (Throwable failure) {
            sessionUnavailable.set(true);
            log.warnOnce("http-session", "HTTP session id unavailable through WebLogic internals: " + failure + "; recording null from now on");
            return null;
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static ClassLoader contextLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClassLoader.getSystemClassLoader();
    }
}
