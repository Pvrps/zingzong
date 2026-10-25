package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries "which trace and span caused this" across a thread handoff inside one server (an
 * {@code @Asynchronous} call, a JMS message to an MDB) through WebLogic's WorkContext, reached
 * reflectively. The one entry, {@value #KEY}, holds {@code <traceId>/<spanId>} of the span active
 * when the handoff happened. Without the WorkContext API every method is a no-op.
 */
public final class WorkContextPropagation {

    static final String KEY = "ca.gc.cra.xz02.devops.wltrace.model";

    /** What a handoff carried in: trace id and causing span id. */
    public static final class Inbound {
        public final String traceId;
        public final String spanId;

        Inbound(String traceId, String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
        }
    }

    private static final AtomicBoolean warned = new AtomicBoolean();

    private final Method getHelper;
    private final Method getMap;
    private final Method put;
    private final Method get;
    private final Method remove;
    private final Method create;
    private final int mode;

    private WorkContextPropagation(ClassLoader loader) throws Exception {
        Class<?> helperClass = Class.forName("weblogic.workarea.WorkContextHelper", true, loader);
        Class<?> mapClass = Class.forName("weblogic.workarea.WorkContextMap", true, loader);
        Class<?> contextClass = Class.forName("weblogic.workarea.WorkContext", true, loader);
        Class<?> factoryClass = Class.forName("weblogic.workarea.PrimitiveContextFactory", true, loader);
        Class<?> modeClass = Class.forName("weblogic.workarea.PropagationMode", true, loader);
        getHelper = helperClass.getMethod("getWorkContextHelper");
        getMap = helperClass.getMethod("getWorkContextMap");
        put = mapClass.getMethod("put", String.class, contextClass, int.class);
        get = mapClass.getMethod("get", String.class);
        remove = mapClass.getMethod("remove", String.class);
        create = factoryClass.getMethod("create", String.class);
        // GLOBAL leaves out WORK, the flag that follows a work-manager handoff (@Asynchronous).
        int m = 0;
        for (String flag : new String[]{"LOCAL", "WORK", "RMI", "JMS_QUEUE", "JMS_TOPIC", "SOAP", "MIME_HEADER"}) {
            try {
                m |= modeClass.getField(flag).getInt(null);
            } catch (NoSuchFieldException ignored) {
                // a flag this version does not have
            }
        }
        mode = m;
    }

    private WorkContextPropagation() {
        getHelper = getMap = put = get = remove = create = null;
        mode = 0;
    }

    /** Bound to the WorkContext API on the system class path, or inert when it is absent. */
    public static WorkContextPropagation create() {
        try {
            return new WorkContextPropagation(ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            AgentLog.info("WorkContext propagation unavailable (" + t + "); traces are not linked across threads");
            return new WorkContextPropagation();
        }
    }

    public Inbound inbound() {
        if (get == null) {
            return null;
        }
        try {
            Object context = get.invoke(map(), KEY);
            if (context == null) {
                return null;
            }
            Object value = context.getClass().getMethod("get").invoke(context);
            String s = value == null ? null : value.toString();
            int slash = s == null ? -1 : s.indexOf('/');
            return slash < 0 ? null : new Inbound(s.substring(0, slash), s.substring(slash + 1));
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    /** The active span of the thread's trace changed: make it visible to handoffs. */
    public void publish(String traceId, String spanId) {
        if (put == null) {
            return;
        }
        try {
            put.invoke(map(), KEY, create.invoke(null, traceId + "/" + spanId), mode);
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** The trace completed on this thread. */
    public void clear() {
        if (remove == null) {
            return;
        }
        try {
            remove.invoke(map(), KEY);
        } catch (Throwable t) {
            warn(t);
        }
    }

    private Object map() throws Exception {
        return getMap.invoke(getHelper.invoke(null));
    }

    private static void warn(Throwable t) {
        if (warned.compareAndSet(false, true)) {
            AgentLog.warn("WorkContext propagation failed: " + t + "; traces may not be linked across threads");
        }
    }
}
