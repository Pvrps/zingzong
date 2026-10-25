package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code MDListener.onMessage(javax.jms.Message)}. The listener holds the bean's
 * {@code MessageDrivenBeanInfo}; the message is the call's one argument and is recorded as such.
 * No lambdas: this code is copied into the target method.
 */
public final class MessageListenerAdvice {

    private MessageListenerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object listener,
                             @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object message) {
        return TraceRuntime.message().enter(listener, message, null);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] arguments,
                            @Advice.Thrown Throwable thrown) {
        TraceRuntime.message().exit(token, arguments, null, thrown);
    }
}
