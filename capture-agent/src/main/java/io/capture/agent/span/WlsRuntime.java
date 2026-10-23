package io.capture.agent.span;

import io.capture.agent.AgentLog;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server facts read reflectively, never linked: current principal (WebLogic), HTTP session
 * id, server name. Every lookup degrades to null; a failure is logged once, not per call.
 */
final class WlsRuntime {

    private static final String SECURITY_CLASS = "weblogic.security.Security";
    private static final String WLS_USER_PRINCIPAL = "weblogic.security.principal.WLSUserImpl";

    private static volatile Method getCurrentSubject;
    private static final AtomicBoolean subjectUnavailable = new AtomicBoolean();
    private static final AtomicBoolean subjectWarned = new AtomicBoolean();

    private WlsRuntime() {
    }

    static String serverName() {
        return System.getProperty("weblogic.Name");
    }

    /** User name of the current WebLogic subject, or null. */
    static String principal() {
        if (subjectUnavailable.get()) {
            return null;
        }
        try {
            Method method = getCurrentSubject;
            if (method == null) {
                Class<?> security = Class.forName(SECURITY_CLASS, true, loader());
                method = security.getMethod("getCurrentSubject");
                getCurrentSubject = method;
            }
            Object subject = method.invoke(null);
            if (subject == null) {
                return null;
            }
            Method getPrincipals = subject.getClass().getMethod("getPrincipals");
            Set<?> principals = (Set<?>) getPrincipals.invoke(subject);
            String first = null;
            for (Object p : principals) {
                if (!(p instanceof Principal)) {
                    continue;
                }
                String name = ((Principal) p).getName();
                if (p.getClass().getName().equals(WLS_USER_PRINCIPAL)) {
                    return name;
                }
                if (first == null) {
                    first = name;
                }
            }
            return first;
        } catch (Throwable t) {
            subjectUnavailable.set(true);
            if (subjectWarned.compareAndSet(false, true)) {
                AgentLog.warn("principal unavailable (" + SECURITY_CLASS + ".getCurrentSubject): " + t + "; recording null from now on");
            }
            return null;
        }
    }

    private static final AtomicBoolean sessionUnavailable = new AtomicBoolean();
    private static final AtomicBoolean sessionWarned = new AtomicBoolean();

    /**
     * Id of the HTTP session the current thread's request belongs to, or null (no request,
     * no session yet, not WebLogic). Phase 4 stopgap through WebLogic internals, recorded in
     * docs/container-hook.md; Phase 5's servlet hook replaces it. Never creates a session.
     *
     * Path (14.1.2.0.0): ExecuteThread.getCurrentWork() → WlsRequestExecutor.connectionHandler
     * → AbstractHttpConnectionHandler.getServletRequest() → HttpServletRequest.getSession(false).
     */
    static String httpSessionId() {
        if (sessionUnavailable.get()) {
            return null;
        }
        try {
            Thread thread = Thread.currentThread();
            if (!thread.getClass().getName().equals("weblogic.work.ExecuteThread")) {
                return null;
            }
            Method getCurrentWork = thread.getClass().getMethod("getCurrentWork");
            Object work = getCurrentWork.invoke(thread);
            if (work == null || !work.getClass().getName().endsWith("WlsRequestExecutor")) {
                return null;
            }
            java.lang.reflect.Field handlerField = work.getClass().getDeclaredField("connectionHandler");
            handlerField.setAccessible(true);
            Object handler = handlerField.get(work);
            if (handler == null) {
                return null;
            }
            Method getServletRequest = handler.getClass().getMethod("getServletRequest");
            getServletRequest.setAccessible(true);
            Object request = getServletRequest.invoke(handler);
            if (request == null) {
                return null;
            }
            Method getSession = request.getClass().getMethod("getSession", boolean.class);
            getSession.setAccessible(true);
            Object session = getSession.invoke(request, false);
            if (session == null) {
                return null;
            }
            Method getId = session.getClass().getMethod("getId");
            getId.setAccessible(true);
            return (String) getId.invoke(session);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // The server objected to this particular request (recycled, upgraded, ...): just no id.
            return null;
        } catch (Throwable t) {
            sessionUnavailable.set(true);
            if (sessionWarned.compareAndSet(false, true)) {
                AgentLog.warn("HTTP session id unavailable through WebLogic internals: " + t + "; recording null from now on");
            }
            return null;
        }
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClassLoader.getSystemClassLoader();
    }
}
