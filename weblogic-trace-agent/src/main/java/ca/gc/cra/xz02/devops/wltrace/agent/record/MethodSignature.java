package ca.gc.cra.xz02.devops.wltrace.agent.record;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The method a span records arguments and result for: its name, its declared parameter and
 * return types, and its text {@code ReturnType name(ParamType, ...)} with fully qualified
 * names. An unresolved signature knows only the name and descriptor and types every value
 * as {@code Object}.
 */
public final class MethodSignature {

    public final String name;
    /** The resolved method, null when unresolved. */
    public final Method method;
    private final Class<?>[] parameterTypes;
    private final Class<?> returnType;
    public final String text;

    private MethodSignature(String name, Method method, Class<?>[] parameterTypes, Class<?> returnType, String text) {
        this.name = name;
        this.method = method;
        this.parameterTypes = parameterTypes;
        this.returnType = returnType;
        this.text = text;
    }

    public static MethodSignature of(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(", "));
        String text = method.getReturnType().getTypeName() + " " + method.getName() + "(" + parameters + ")";
        return new MethodSignature(method.getName(), method, method.getParameterTypes(), method.getReturnType(), text);
    }

    /** A method that could not be resolved: the name followed by its JVM descriptor. */
    public static MethodSignature unknown(String name, String descriptor) {
        return new MethodSignature(name, null, null, null, name + descriptor);
    }

    public boolean resolved() {
        return parameterTypes != null;
    }

    /** The declared type of the parameter at the index; {@code Object} when unresolved or out of range. */
    public Class<?> parameterType(int index) {
        return resolved() && index < parameterTypes.length ? parameterTypes[index] : Object.class;
    }

    /** The declared return type; {@code Object} when unresolved. */
    public Class<?> returnType() {
        return returnType == null ? Object.class : returnType;
    }

    public boolean returnsVoid() {
        return returnType == void.class;
    }
}
