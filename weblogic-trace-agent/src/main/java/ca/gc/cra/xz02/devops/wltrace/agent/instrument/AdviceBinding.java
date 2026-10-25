package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** An advice class and the types and methods it is inlined into. */
public final class AdviceBinding {

    private final TypeMatcher types;
    private final ElementMatcher<? super MethodDescription> methods;
    private final Class<?> advice;

    public AdviceBinding(TypeMatcher types, ElementMatcher<? super MethodDescription> methods, Class<?> advice) {
        this.types = types;
        this.methods = methods;
        this.advice = advice;
    }

    public TypeMatcher types() {
        return types;
    }

    public ElementMatcher<? super MethodDescription> methods() {
        return methods;
    }

    /** The {@code *Advice} class; located through the system class loader, where the agent jar also sits. */
    public Class<?> advice() {
        return advice;
    }
}
