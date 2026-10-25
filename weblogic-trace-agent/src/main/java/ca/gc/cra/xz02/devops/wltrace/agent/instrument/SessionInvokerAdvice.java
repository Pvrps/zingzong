package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code SessionLocalMethodInvoker.invoke} and {@code SessionRemoteMethodInvoker.invoke}
 * {@code (Invokable, BaseLocal/RemoteObject, InvocationWrapper, Object[], int)}. Arguments 0 to 3
 * are the generated client object, the container's local or remote object, the invocation wrapper
 * and the call arguments. No lambdas: this code is copied into the target method.
 */
public final class SessionInvokerAdvice {

    private SessionInvokerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object invokable,
                             @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object clientObject,
                             @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) Object invocationWrapper) {
        return TraceRuntime.ejb().enter(invokable, clientObject, invocationWrapper);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.Argument(value = 3, typing = Assigner.Typing.DYNAMIC) Object[] arguments,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                            @Advice.Thrown Throwable thrown) {
        TraceRuntime.ejb().exit(token, arguments, result, thrown);
    }
}
