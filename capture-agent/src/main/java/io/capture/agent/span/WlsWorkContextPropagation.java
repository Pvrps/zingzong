package io.capture.agent.span;

import io.capture.agent.AgentLog;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link TracePropagation} over WebLogic's WorkContext (the {@code weblogic.workarea} API,
 * reached reflectively): the thread's {@code WorkContextMap} travels with the work the server
 * hands to other threads ({@code @Asynchronous} invocations, JMS messages to MDBs, and also
 * RMI, which is not relied on). The agent keeps one entry, {@value #KEY}, whose value is
 * {@code <traceId>/<spanId>} of the span active when the handoff happened.
 *
 * Path (12.2.1.4.0, 14.1.2.0.0; docs/container-hook.md): {@code WorkContextHelper
 * .getWorkContextHelper().getWorkContextMap()}, {@code WorkContextMap.put(key, WorkContext,
 * LOCAL | WORK | RMI | JMS_QUEUE | JMS_TOPIC | SOAP | MIME_HEADER)} / {@code get(key)} / {@code remove(key)},
 * {@code PrimitiveContextFactory.create(String)}, {@code PrimitiveWorkContext.get()}.
 * Every failure degrades to "no propagation" and is logged once.
 */
public final class WlsWorkContextPropagation implements TracePropagation {

    static final String KEY = "io.capture.trace";

    private static final AtomicBoolean warned = new AtomicBoolean();

    private final Method getHelper;
    private final Method getMap;
    private final Method put;
    private final Method get;
    private final Method remove;
    private final Method create;
    private final int mode;

    private WlsWorkContextPropagation(ClassLoader loader) throws Exception {
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
        // Not GLOBAL: on 12.2.1.4 and 14.1.2 GLOBAL == DEFAULT == 212 (RMI | JMS_QUEUE | SOAP | MIME_HEADER)
        // and leaves out WORK, the flag that follows a work manager handoff (@Asynchronous).
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

    /** The WebLogic implementation when the API is on the system class path, else {@link TracePropagation#NONE}. */
    public static TracePropagation create() {
        try {
            return new WlsWorkContextPropagation(ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            AgentLog.info("WorkContext propagation unavailable (" + t + "); traces are not linked across threads");
            return NONE;
        }
    }

    @Override
    public Inbound inbound() {
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

    @Override
    public void publish(String traceId, String spanId) {
        try {
            put.invoke(map(), KEY, create.invoke(null, traceId + "/" + spanId), mode);
        } catch (Throwable t) {
            warn(t);
        }
    }

    @Override
    public void clear() {
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
