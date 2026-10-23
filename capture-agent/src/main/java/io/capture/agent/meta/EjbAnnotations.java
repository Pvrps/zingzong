package io.capture.agent.meta;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facts an EJB class states about itself, read from its annotations by name (never linked):
 * the EJB name, its business interfaces with their view, the transaction attribute of a method.
 */
public final class EjbAnnotations {

    private static final String[] COMPONENT = {"javax.ejb.Stateless", "javax.ejb.Stateful", "javax.ejb.Singleton", "javax.ejb.MessageDriven"};
    private static final String LOCAL = "javax.ejb.Local";
    private static final String REMOTE = "javax.ejb.Remote";
    private static final String TX_ATTRIBUTE = "javax.ejb.TransactionAttribute";
    private static final String TX_MANAGEMENT = "javax.ejb.TransactionManagement";

    private EjbAnnotations() {
    }

    /** The {@code name} member of the component annotation, else the simple class name. */
    public static String ejbName(Class<?> beanClass) {
        for (Annotation a : safeAnnotations(beanClass)) {
            for (String component : COMPONENT) {
                if (a.annotationType().getName().equals(component)) {
                    String name = member(a, "name");
                    return name == null || name.isEmpty() ? beanClass.getSimpleName() : name;
                }
            }
        }
        return beanClass.getSimpleName();
    }

    /** Business interfaces → LOCAL/REMOTE, from @Local/@Remote on the interfaces or on the bean. */
    public static Map<String, String> interfaces(Class<?> beanClass) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Annotation a : safeAnnotations(beanClass)) {
            String name = a.annotationType().getName();
            if (name.equals(LOCAL) || name.equals(REMOTE)) {
                Object value = memberObject(a, "value");
                if (value instanceof Class[]) {
                    for (Class<?> c : (Class<?>[]) value) {
                        result.put(c.getName(), name.equals(LOCAL) ? BeanMeta.LOCAL : BeanMeta.REMOTE);
                    }
                }
            }
        }
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                String view = viewOf(i);
                if (view != null && !result.containsKey(i.getName())) {
                    result.put(i.getName(), view);
                }
            }
        }
        return result;
    }

    /** LOCAL/REMOTE if the interface carries @Local/@Remote, else null. */
    public static String viewOf(Class<?> iface) {
        for (Annotation a : safeAnnotations(iface)) {
            String name = a.annotationType().getName();
            if (name.equals(LOCAL)) {
                return BeanMeta.LOCAL;
            }
            if (name.equals(REMOTE)) {
                return BeanMeta.REMOTE;
            }
        }
        return null;
    }

    /**
     * The transaction attribute that applies to a bean method: the method's own, else the
     * class's, else REQUIRED (the EJB default); "BEAN" for bean-managed transactions.
     */
    public static String transactionAttribute(Class<?> beanClass, Method method) {
        for (Annotation a : safeAnnotations(beanClass)) {
            if (a.annotationType().getName().equals(TX_MANAGEMENT)) {
                Object value = memberObject(a, "value");
                if (value != null && value.toString().equals("BEAN")) {
                    return "BEAN";
                }
            }
        }
        if (method != null) {
            Method declared = method;
            if (method.getDeclaringClass() != beanClass) {
                try {
                    declared = beanClass.getMethod(method.getName(), method.getParameterTypes());
                } catch (Throwable ignored) {
                    // interface method with no bean counterpart: use what we have
                }
            }
            String own = txAttribute(declared.getAnnotations());
            if (own != null) {
                return own;
            }
        }
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            String onClass = txAttribute(safeAnnotations(c));
            if (onClass != null) {
                return onClass;
            }
        }
        return "REQUIRED";
    }

    private static String txAttribute(Annotation[] annotations) {
        for (Annotation a : annotations) {
            if (a.annotationType().getName().equals(TX_ATTRIBUTE)) {
                Object value = memberObject(a, "value");
                return value == null ? "REQUIRED" : value.toString();
            }
        }
        return null;
    }

    private static Annotation[] safeAnnotations(Class<?> type) {
        try {
            return type.getAnnotations();
        } catch (Throwable t) {
            return new Annotation[0];
        }
    }

    private static String member(Annotation a, String name) {
        Object value = memberObject(a, name);
        return value == null ? null : value.toString();
    }

    private static Object memberObject(Annotation a, String name) {
        try {
            Method m = a.annotationType().getMethod(name);
            return m.invoke(a);
        } catch (Throwable t) {
            return null;
        }
    }
}
