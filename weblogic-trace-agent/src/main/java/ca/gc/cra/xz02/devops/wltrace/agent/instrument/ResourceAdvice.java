package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into the public instance methods of JAX-RS resource classes and JAX-WS endpoints. The
 * origin, {@code <class>|<method>|<descriptor>}, is a compile-time constant per call site from
 * which the recorder resolves the method. No lambdas: this code is copied into the target method.
 */
public final class ResourceAdvice {

    private ResourceAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This(optional = true) Object resource,
                             @Advice.Origin("#t|#m|#d") String origin) {
        return TraceRuntime.resource().enter(resource, origin, null);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] arguments,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                            @Advice.Thrown Throwable thrown) {
        TraceRuntime.resource().exit(token, arguments, result, thrown);
    }
}
