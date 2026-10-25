package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Carries "which trace and span caused this" across a thread handoff inside one server (an
 * {@code @Asynchronous} call, a JMS message to an MDB) through WebLogic's WorkContext, reached
 * reflectively. The one entry, {@value #KEY}, holds {@code <traceId>/<spanId>} of the span
 * active when the handoff happened. Without the WorkContext API every method is a no-op.
 */
public final class WorkContextPropagation {

    public static final String KEY = "ca.gc.cra.xz02.devops.wltrace";
    /**
     * The flags of {@code PropagationMode} the entry travels with. {@code GLOBAL} leaves out
     * {@code WORK}, the flag that follows a work-manager handoff, which is where
     * {@code @Asynchronous} runs.
     */
    private static final String[] PROPAGATION_FLAGS = {"LOCAL", "WORK", "RMI", "JMS_QUEUE", "JMS_TOPIC", "SOAP", "MIME_HEADER"};

    /** What a handoff carried in: the trace and the causing span. */
    public static final class Inbound {
        public final String traceId;
        public final String spanId;

        Inbound(String traceId, String spanId) {
            this.traceId = traceId;
            this.spanId = spanId;
        }
    }

    private final AgentLog log;
    private final Method getHelper;
    private final Method getMap;
    private final Method put;
    private final Method get;
    private final Method remove;
    private final Method create;
    private final int mode;

    private WorkContextPropagation(AgentLog log, ClassLoader loader) throws Exception {
        this.log = log;
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
        int flags = 0;
        for (String flag : PROPAGATION_FLAGS) {
            try {
                flags |= modeClass.getField(flag).getInt(null);
            } catch (NoSuchFieldException absent) {
                // a flag this version does not have
            }
        }
        mode = flags;
    }

    private WorkContextPropagation(AgentLog log) {
        this.log = log;
        getHelper = getMap = put = get = remove = create = null;
        mode = 0;
    }

    /** Bound to the WorkContext API on the system class path, or inert when it is absent. */
    public static WorkContextPropagation create(AgentLog log) {
        try {
            return new WorkContextPropagation(log, ClassLoader.getSystemClassLoader());
        } catch (Throwable failure) {
            log.info("WorkContext propagation absent (" + failure + "); traces are not linked across threads");
            return new WorkContextPropagation(log);
        }
    }

    /** The trace and span that caused the current thread's work, when a handoff carried them in. */
    public Optional<Inbound> inbound() {
        if (get == null) {
            return Optional.empty();
        }
        try {
            Object context = get.invoke(map(), KEY);
            if (context == null) {
                return Optional.empty();
            }
            Object value = context.getClass().getMethod("get").invoke(context);
            String text = value == null ? null : value.toString();
            int slash = text == null ? -1 : text.indexOf('/');
            return slash < 0 ? Optional.empty() : Optional.of(new Inbound(text.substring(0, slash), text.substring(slash + 1)));
        } catch (Throwable failure) {
            warn(failure);
            return Optional.empty();
        }
    }

    /** The active span of the thread's trace changed: makes it visible to handoffs. */
    public void publish(String traceId, String spanId) {
        if (put == null) {
            return;
        }
        try {
            put.invoke(map(), KEY, create.invoke(null, traceId + "/" + spanId), mode);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    /** The trace completed on this thread. */
    public void clear() {
        if (remove == null) {
            return;
        }
        try {
            remove.invoke(map(), KEY);
        } catch (Throwable failure) {
            warn(failure);
        }
    }

    private Object map() throws Exception {
        return getMap.invoke(getHelper.invoke(null));
    }

    private void warn(Throwable failure) {
        log.warnOnce("work-context", "WorkContext propagation failed: " + failure + "; traces may not be linked across threads");
    }
}
