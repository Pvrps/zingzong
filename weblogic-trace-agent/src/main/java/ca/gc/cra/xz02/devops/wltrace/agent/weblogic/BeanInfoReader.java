package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads the container's {@code BeanInfo} family by member name: {@code SessionBeanInfo} for
 * business interfaces, bound names and bean type, {@code ClientDrivenBeanInfo} for EJB 2.x
 * component interfaces, {@code MessageDrivenBeanInfo} by its marker interface. Also finds the
 * {@code BeanInfo} behind the objects the container hands out.
 */
public final class BeanInfoReader {

    static final String BEAN_INFO = "weblogic.ejb.container.interfaces.BeanInfo";
    static final String MESSAGE_DRIVEN_BEAN_INFO = "weblogic.ejb.container.interfaces.MessageDrivenBeanInfo";
    private static final int SEARCH_DEPTH = 4;

    private BeanInfoReader() {
    }

    /**
     * The BeanInfo behind an object: the object itself, the {@code beanInfo} field of a
     * BaseLocalObject or BaseRemoteObject, or a bounded search through the wrapping the container
     * puts around a JNDI lookup result (proxy, RemoteBusinessIntfProxy, Impl_CBV, Impl).
     */
    public static Object findBeanInfo(Object candidate) {
        return findBeanInfo(candidate, 0);
    }

    private static Object findBeanInfo(Object candidate, int depth) {
        if (candidate == null || depth > SEARCH_DEPTH) {
            return null;
        }
        if (Reflection.implementsNamed(candidate.getClass(), BEAN_INFO)) {
            return candidate;
        }
        if (Proxy.isProxyClass(candidate.getClass())) {
            return findBeanInfo(Proxy.getInvocationHandler(candidate), depth + 1);
        }
        Object direct = Reflection.field(candidate, "beanInfo");
        if (direct != null) {
            return direct;
        }
        for (Class<?> current = candidate.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object held = field.get(candidate);
                    if (held == null || held instanceof ClassLoader || held.getClass().isArray()
                            || held.getClass().getName().startsWith("java.")) {
                        continue;
                    }
                    Object found = findBeanInfo(held, depth + 1);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                    // next field
                }
            }
        }
        return null;
    }

    /** {@code getBusinessLocals()} and {@code getBusinessRemotes()}: interface name to view. */
    public static Map<String, String> businessInterfaces(Object beanInfo) {
        Map<String, String> result = new LinkedHashMap<>();
        addInterfaces(result, Reflection.call(beanInfo, "getBusinessLocals"), BeanMetadata.LOCAL);
        addInterfaces(result, Reflection.call(beanInfo, "getBusinessRemotes"), BeanMetadata.REMOTE);
        return result;
    }

    private static void addInterfaces(Map<String, String> into, Object classes, String view) {
        if (classes instanceof Collection) {
            for (Object member : (Collection<?>) classes) {
                if (member instanceof Class) {
                    into.put(((Class<?>) member).getName(), view);
                }
            }
        }
    }

    /** {@code getBusinessJNDINames()}: adds every bound name to {@code names} and returns interface name to view. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> businessJndiNames(Object beanInfo, Set<String> names) {
        Map<String, String> views = new LinkedHashMap<>();
        try {
            Method byInterface = beanInfo.getClass().getMethod("getBusinessJNDINames");
            byInterface.setAccessible(true);
            Method isLocal = beanInfo.getClass().getMethod("isLocalClientView", Class.class);
            isLocal.setAccessible(true);
            for (Map.Entry<Class<?>, Set<String>> entry : ((Map<Class<?>, Set<String>>) byInterface.invoke(beanInfo)).entrySet()) {
                boolean local = Boolean.TRUE.equals(isLocal.invoke(beanInfo, entry.getKey()));
                views.put(entry.getKey().getName(), local ? BeanMetadata.LOCAL : BeanMetadata.REMOTE);
                names.addAll(entry.getValue());
            }
        } catch (Throwable ignored) {
            // not a session bean: the deterministic names are all there is
        }
        return views;
    }

    /** {@code isStateful}, {@code isSingleton}, {@code isStateless}; a MessageDrivenBeanInfo is MESSAGE_DRIVEN; null when unknown. */
    public static String beanType(Object beanInfo) {
        if (beanInfo == null) {
            return null;
        }
        if (Reflection.implementsNamed(beanInfo.getClass(), MESSAGE_DRIVEN_BEAN_INFO)) {
            return "MESSAGE_DRIVEN";
        }
        String[][] probes = {{"isStateful", "STATEFUL"}, {"isSingleton", "SINGLETON"}, {"isStateless", "STATELESS"}};
        for (String[] probe : probes) {
            if (Boolean.TRUE.equals(Reflection.call(beanInfo, probe[0]))) {
                return probe[1];
            }
        }
        return null;
    }

    /** The EJB 2.x component interface of one view ({@code getRemoteInterfaceClass} / {@code getLocalInterfaceClass}), or null. */
    public static Class<?> componentInterface(Object beanInfo, String getter) {
        Object component = Reflection.call(beanInfo, getter);
        return component instanceof Class ? (Class<?>) component : null;
    }
}
