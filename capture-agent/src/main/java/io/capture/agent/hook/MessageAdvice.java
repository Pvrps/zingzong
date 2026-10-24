package io.capture.agent.hook;

import io.capture.agent.span.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code weblogic.ejb.container.internal.MDListener.onMessage(javax.jms.Message)}:
 * where every JMS message reaches a message-driven bean (docs/container-hook.md). The listener
 * holds the bean's {@code MessageDrivenBeanInfo}; the message is the call's one argument.
 */
public final class MessageAdvice {

    private MessageAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object listener,
                             @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object message) {
        return Recorder.enterMessage(listener, message);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object message,
                            @Advice.Thrown Throwable thrown) {
        Recorder.exitMessage(token, message, thrown);
    }
}
