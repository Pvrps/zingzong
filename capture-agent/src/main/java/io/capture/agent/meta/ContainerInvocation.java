package io.capture.agent.meta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads WebLogic's EJB invocation objects reflectively, by getter and field name
 * (docs/container-hook.md, 14.1.2.0.0). The invoker's arguments are the generated client
 * object ({@code Invokable}: implements the business interface, directly for a local view or
 * through a generated {@code *RIntf} for a remote one), the container's
 * {@code BaseLocalObject}/{@code BaseRemoteObject} (field {@code beanInfo} →
 * {@code BeanInfo.getBeanClassName()}) and the {@code InvocationWrapper}
 * ({@code getMethodDescriptor()} → {@code getApplicationName()/getModuleId()/getEjbName()/
 * getMethod()/getTransactionPolicy().getTxAttribute()}, {@code isLocal()}).
 */
public final class ContainerInvocation {

    public final String application;
    public final String moduleId;
    public final String ejbName;
    public final Method method;
    public final String transactionAttribute;
    public final boolean local;
    public final Class<?> beanClass;
    private final Object invokable;

    private ContainerInvocation(String application, String moduleId, String ejbName, Method method,
                                String transactionAttribute, boolean local, Class<?> beanClass, Object invokable) {
        this.application = application;
        this.moduleId = moduleId;
        this.ejbName = ejbName;
        this.method = method;
        this.transactionAttribute = transactionAttribute;
        this.local = local;
        this.beanClass = beanClass;
        this.invokable = invokable;
    }

    /** Null when the objects do not look like the expected container classes. */
    public static ContainerInvocation read(Object invokable, Object clientObject, Object wrapper) {
        try {
            Object descriptor = call(wrapper, "getMethodDescriptor");
            if (descriptor == null) {
                return null;
            }
            String application = str(call(descriptor, "getApplicationName"));
            String moduleId = str(call(descriptor, "getModuleId"));
            String ejbName = str(call(descriptor, "getEjbName"));
            Method method = (Method) call(descriptor, "getMethod");
            Object policy = call(descriptor, "getTransactionPolicy");
            String txAttribute = policy == null ? null : str(call(policy, "getTxAttribute"));
            Object isLocal = call(wrapper, "isLocal");
            boolean local = isLocal instanceof Boolean ? (Boolean) isLocal
                    : !superclassNamed(clientObject, "weblogic.ejb.container.internal.BaseRemoteObject");
            Class<?> beanClass = beanClassOf(clientObject, invokable);
            return new ContainerInvocation(application, moduleId, ejbName, method, txAttribute, local, beanClass, invokable);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The business interface the call came in through, among {@code candidates} (interface
     * name → view, from {@link BeanMeta#interfaces}): the one the client object implements
     * (local view), else the one named by its generated remote interface
     * {@code <pkg>.<Ejb>_<hash>_<IfaceSimpleName>RIntf}, which extends only java.rmi.Remote
     * (remote view); failing that, the only candidate of the call's view.
     */
    public String businessInterface(Map<String, String> candidates) {
        Set<Class<?>> implemented = new LinkedHashSet<Class<?>>();
        collectInterfaces(invokable.getClass(), implemented);
        if (candidates == null || candidates.isEmpty()) {
            for (Class<?> i : implemented) {
                if (EjbAnnotations.viewOf(i) != null) {
                    return i.getName();
                }
            }
            return null;
        }
        for (Class<?> i : implemented) {
            if (candidates.containsKey(i.getName())) {
                return i.getName();
            }
        }
        for (Class<?> i : implemented) {
            Matcher m = GENERATED_REMOTE.matcher(i.getName());
            if (m.matches()) {
                String resolved = InvokedInterface.resolve(m.group(2), candidates);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        String view = local ? BeanMeta.LOCAL : BeanMeta.REMOTE;
        String only = null;
        for (Map.Entry<String, String> e : candidates.entrySet()) {
            if (view.equals(e.getValue())) {
                if (only != null) {
                    return null;
                }
                only = e.getKey();
            }
        }
        return only;
    }

    private static final Pattern GENERATED_REMOTE = Pattern.compile("(.+)\\.[^.]+_[0-9a-z]+_([^.]+)RIntf$");

    private static void collectInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (into.add(i)) {
                    collectInterfaces(i, into);
                }
            }
        }
    }

    private static Class<?> beanClassOf(Object clientObject, Object invokable) {
        Object beanInfo = fieldUp(clientObject, "beanInfo");
        String name = beanInfo == null ? null : str(call(beanInfo, "getBeanClassName"));
        if (name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, invokable.getClass().getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    static Object call(Object target, String getter) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(getter);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object fieldUp(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // climb
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean superclassNamed(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
