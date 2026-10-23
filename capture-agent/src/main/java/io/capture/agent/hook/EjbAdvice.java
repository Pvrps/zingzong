package io.capture.agent.hook;

import io.capture.agent.span.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into every public instance method of an EJB class. Keep it tiny: it is copied
 * into application bytecode, so every line here is duplicated per method. Delegate
 * immediately to {@link Recorder}, which must be visible from the application loader.
 * {@code suppress} makes Byte Buddy swallow anything thrown here.
 *
 * The origin strings ({@code #t} type, {@code #m} method, {@code #s} parameter list,
 * {@code #d} descriptor) are compile-time constants per call site; the Recorder resolves
 * the reflective Method from them lazily and caches it per bean Class.
 */
public final class EjbAdvice {

    private EjbAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This(optional = true) Object self,
                             @Advice.Origin("#t") String beanClass,
                             @Advice.Origin("#m") String method,
                             @Advice.Origin("#d") String descriptor) {
        return Recorder.enter(self, beanClass, method, descriptor);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.This(optional = true) Object self,
                            @Advice.Origin("#t") String beanClass,
                            @Advice.Origin("#m") String method,
                            @Advice.Origin("#d") String descriptor,
                            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                            @Advice.Thrown Throwable thrown) {
        Recorder.exit(token, self, beanClass, method, descriptor, args, result, thrown);
    }
}
