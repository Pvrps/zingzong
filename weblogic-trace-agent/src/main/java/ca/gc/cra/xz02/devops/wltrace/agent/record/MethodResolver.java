package ca.gc.cra.xz02.devops.wltrace.agent.record;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@link MethodSignature} behind a call site from the bean class and either the
 * method name and JVM descriptor an advice passes as constants, or the interface method the
 * container passes. Cached per bean {@link Class} object, released with it.
 */
public final class MethodResolver {

    private final ClassValue<Map<String, MethodSignature>> cache = new ClassValue<Map<String, MethodSignature>>() {
        @Override
        protected Map<String, MethodSignature> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    /** The user's bean class behind an instance, walking up from its runtime class, which may be a container subclass. */
    public static Class<?> beanClassOf(Object instance, String beanClassName) {
        if (instance == null) {
            return null;
        }
        for (Class<?> current = instance.getClass(); current != null; current = current.getSuperclass()) {
            if (current.getName().equals(beanClassName)) {
                return current;
            }
        }
        return null;
    }

    /** The declared method with this name and descriptor; unknown when the class is null or has none. */
    public MethodSignature resolve(Class<?> beanClass, String name, String descriptor) {
        if (beanClass == null) {
            return MethodSignature.unknown(name, descriptor);
        }
        return cache.get(beanClass).computeIfAbsent(name + descriptor, key -> find(beanClass, name, descriptor));
    }

    /**
     * The bean's own method matching an interface method (same name and parameter types), so that
     * every hook describes the same declaration; the interface method when the bean has none.
     */
    public MethodSignature resolve(Class<?> beanClass, Method interfaceMethod) {
        if (beanClass == null) {
            return MethodSignature.of(interfaceMethod);
        }
        return cache.get(beanClass).computeIfAbsent(interfaceMethod.getName() + descriptorOf(interfaceMethod), key -> {
            try {
                return MethodSignature.of(beanClass.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes()));
            } catch (Throwable absent) {
                return MethodSignature.of(interfaceMethod);
            }
        });
    }

    private static MethodSignature find(Class<?> beanClass, String name, String descriptor) {
        try {
            for (Method method : beanClass.getDeclaredMethods()) {
                if (method.getName().equals(name) && descriptorOf(method).equals(descriptor)) {
                    return MethodSignature.of(method);
                }
            }
        } catch (Throwable ignored) {
            // unknown below
        }
        return MethodSignature.unknown(name, descriptor);
    }

    static String descriptorOf(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : method.getParameterTypes()) {
            appendDescriptor(descriptor, parameterType);
        }
        descriptor.append(')');
        appendDescriptor(descriptor, method.getReturnType());
        return descriptor.toString();
    }

    private static void appendDescriptor(StringBuilder descriptor, Class<?> type) {
        if (type.isArray()) {
            descriptor.append(type.getName().replace('.', '/'));
        } else if (type.isPrimitive()) {
            descriptor.append(primitiveDescriptor(type));
        } else {
            descriptor.append('L').append(type.getName().replace('.', '/')).append(';');
        }
    }

    private static char primitiveDescriptor(Class<?> type) {
        if (type == int.class) {
            return 'I';
        }
        if (type == long.class) {
            return 'J';
        }
        if (type == boolean.class) {
            return 'Z';
        }
        if (type == void.class) {
            return 'V';
        }
        if (type == double.class) {
            return 'D';
        }
        if (type == float.class) {
            return 'F';
        }
        if (type == short.class) {
            return 'S';
        }
        if (type == byte.class) {
            return 'B';
        }
        return 'C';
    }
}
