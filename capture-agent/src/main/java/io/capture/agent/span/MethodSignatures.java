package io.capture.agent.span;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@link Method} behind an advice call site from the bean class, method name
 * and JVM descriptor the advice passes as constants. Cached per bean {@link Class} with a
 * {@link ClassValue}: keyed by the Class object, released with it.
 */
final class MethodSignatures {

    private static final ClassValue<Map<String, MethodInfo>> CACHE = new ClassValue<Map<String, MethodInfo>>() {
        @Override
        protected Map<String, MethodInfo> computeValue(Class<?> type) {
            return new ConcurrentHashMap<String, MethodInfo>();
        }
    };

    private MethodSignatures() {
    }

    /** The user's bean class: walk up from the runtime class (possibly a container subclass). */
    static Class<?> beanClass(Object self, String beanClassName) {
        if (self == null) {
            return null;
        }
        for (Class<?> c = self.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(beanClassName)) {
                return c;
            }
        }
        return null;
    }

    static MethodInfo resolve(Class<?> beanClass, String name, String descriptor) {
        if (beanClass == null) {
            return MethodInfo.unknown(name, descriptor);
        }
        Map<String, MethodInfo> methods = CACHE.get(beanClass);
        String key = name + descriptor;
        MethodInfo info = methods.get(key);
        if (info == null) {
            info = find(beanClass, name, descriptor);
            methods.put(key, info);
        }
        return info;
    }

    private static MethodInfo find(Class<?> beanClass, String name, String descriptor) {
        try {
            for (Method m : beanClass.getDeclaredMethods()) {
                if (m.getName().equals(name) && descriptorOf(m).equals(descriptor)) {
                    return MethodInfo.of(m);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return MethodInfo.unknown(name, descriptor);
    }

    static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            appendDescriptor(sb, p);
        }
        sb.append(')');
        appendDescriptor(sb, m.getReturnType());
        return sb.toString();
    }

    private static void appendDescriptor(StringBuilder sb, Class<?> c) {
        if (c.isArray()) {
            sb.append(c.getName().replace('.', '/'));
        } else if (c.isPrimitive()) {
            sb.append(primitiveDescriptor(c));
        } else {
            sb.append('L').append(c.getName().replace('.', '/')).append(';');
        }
    }

    private static char primitiveDescriptor(Class<?> c) {
        if (c == int.class) return 'I';
        if (c == long.class) return 'J';
        if (c == boolean.class) return 'Z';
        if (c == void.class) return 'V';
        if (c == double.class) return 'D';
        if (c == float.class) return 'F';
        if (c == short.class) return 'S';
        if (c == byte.class) return 'B';
        return 'C';
    }

    static final class MethodInfo {
        final String name;
        final Class<?>[] parameterTypes;
        final Type[] genericParameterTypes;
        final Class<?> returnType;
        /** "ReturnType name(ParamType, ...)" with fully qualified names; descriptor when unresolved. */
        final String signature;

        private MethodInfo(String name, Class<?>[] parameterTypes, Type[] genericParameterTypes, Class<?> returnType, String signature) {
            this.name = name;
            this.parameterTypes = parameterTypes;
            this.genericParameterTypes = genericParameterTypes;
            this.returnType = returnType;
            this.signature = signature;
        }

        static MethodInfo of(Method m) {
            StringBuilder sig = new StringBuilder(m.getReturnType().getTypeName()).append(' ').append(m.getName()).append('(');
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sig.append(", ");
                }
                sig.append(params[i].getTypeName());
            }
            sig.append(')');
            return new MethodInfo(m.getName(), params, m.getGenericParameterTypes(), m.getReturnType(), sig.toString());
        }

        static MethodInfo unknown(String name, String descriptor) {
            return new MethodInfo(name, null, null, null, name + descriptor);
        }

        boolean resolved() {
            return parameterTypes != null;
        }

        Class<?> parameterType(int i) {
            return resolved() && i < parameterTypes.length ? parameterTypes[i] : Object.class;
        }

        boolean returnsVoid() {
            return returnType == void.class;
        }
    }
}
