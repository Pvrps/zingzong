package io.capture.agent.hook;

import io.capture.agent.span.Recorder;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into every public instance method of an EJB class. Keep it tiny: it is copied
 * into application bytecode, so every line here is duplicated per method. Delegate
 * immediately to {@link Recorder}, which must be visible from the application loader.
 * {@code suppress} makes Byte Buddy swallow anything thrown here.
 */
public final class EjbAdvice {

    private EjbAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.Origin("#t") String beanClass,
                             @Advice.Origin("#m") String method,
                             @Advice.Origin("#s") String signature) {
        return Recorder.enter(beanClass, method, signature);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.Origin("#t") String beanClass,
                            @Advice.Origin("#m") String method,
                            @Advice.Thrown Throwable thrown) {
        Recorder.exit(token, beanClass, method, thrown);
    }
}
