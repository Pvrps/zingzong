package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflective reads by name that never throw: a public no-argument method, a field anywhere up
 * the class chain, and type tests against a class or interface name. Every WebLogic class the
 * agent touches is reached this way, because none of them is visible at compile time.
 */
public final class Reflection {

    private Reflection() {
    }

    /** Invokes a public no-argument method by name; null when the target is null, the method is absent or the call fails. */
    public static Object call(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Reads a field by name from the object's class or any superclass; null when absent or unreadable. */
    public static Object field(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                // climb to the superclass
            } catch (Throwable failure) {
                return null;
            }
        }
        return null;
    }

    /** True when the type, a superclass or any interface in its hierarchy has the given name. */
    public static boolean implementsNamed(Class<?> type, String interfaceName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(interfaceName)) {
                return true;
            }
            for (Class<?> implemented : current.getInterfaces()) {
                if (implementsNamed(implemented, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the object's class or one of its superclasses has the given name. */
    public static boolean extendsNamed(Object target, String className) {
        for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
            if (current.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    /** {@code toString()} of a value, or null for null. */
    public static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
