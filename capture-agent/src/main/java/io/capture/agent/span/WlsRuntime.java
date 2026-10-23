package io.capture.agent.span;

import io.capture.agent.AgentLog;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server facts read reflectively, never linked: current principal (WebLogic), transaction
 * status (JTA registry via JNDI), server name. Every lookup degrades to null; a failure is
 * logged once, not per call.
 */
final class WlsRuntime {

    private static final String SECURITY_CLASS = "weblogic.security.Security";
    private static final String WLS_USER_PRINCIPAL = "weblogic.security.principal.WLSUserImpl";
    private static final String TSR_JNDI = "java:comp/TransactionSynchronizationRegistry";

    private static final Object UNAVAILABLE = new Object();

    private static volatile Method getCurrentSubject;
    private static final AtomicBoolean subjectUnavailable = new AtomicBoolean();
    private static final AtomicBoolean subjectWarned = new AtomicBoolean();

    private static final AtomicBoolean tsrUnavailable = new AtomicBoolean();
    private static final AtomicBoolean tsrWarned = new AtomicBoolean();
    private static volatile Method getTransactionStatus;
    /** The registry per thread (JNDI lookups are not free); UNAVAILABLE once a thread failed. */
    private static final ThreadLocal<Object> registry = new ThreadLocal<Object>();

    private static final String[] STATUS_NAMES = {
            "ACTIVE", "MARKED_ROLLBACK", "PREPARED", "COMMITTED", "ROLLEDBACK",
            "UNKNOWN", "NO_TRANSACTION", "PREPARING", "COMMITTING", "ROLLING_BACK",
    };

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

    /** JTA status name (ACTIVE, NO_TRANSACTION, ...) of the calling thread, or null. */
    static String transactionStatus() {
        if (tsrUnavailable.get()) {
            return null;
        }
        try {
            Object tsr = registry.get();
            if (tsr == UNAVAILABLE) {
                return null;
            }
            if (tsr == null) {
                try {
                    tsr = new javax.naming.InitialContext().lookup(TSR_JNDI);
                } catch (javax.naming.NamingException e) {
                    // This thread has no component context (not an EE request thread); others may.
                    registry.set(UNAVAILABLE);
                    if (tsrWarned.compareAndSet(false, true)) {
                        AgentLog.warn("transaction status unavailable on thread " + Thread.currentThread().getName()
                                + " (" + TSR_JNDI + "): " + e + "; recording null on such threads");
                    }
                    return null;
                }
                if (tsr == null) {
                    registry.set(UNAVAILABLE);
                    return null;
                }
                registry.set(tsr);
            }
            Method method = getTransactionStatus;
            if (method == null) {
                method = tsr.getClass().getMethod("getTransactionStatus");
                method.setAccessible(true);
                getTransactionStatus = method;
            }
            int status = (Integer) method.invoke(tsr);
            return status >= 0 && status < STATUS_NAMES.length ? STATUS_NAMES[status] : "STATUS_" + status;
        } catch (Throwable t) {
            tsrUnavailable.set(true);
            if (tsrWarned.compareAndSet(false, true)) {
                AgentLog.warn("transaction status unavailable (" + TSR_JNDI + "): " + t + "; recording null from now on");
            }
            return null;
        }
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ClassLoader.getSystemClassLoader();
    }
}
