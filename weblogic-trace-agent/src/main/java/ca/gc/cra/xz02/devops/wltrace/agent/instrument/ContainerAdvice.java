package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.EjbRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke} and
 * {@code SessionRemoteMethodInvoker.invoke(Invokable, BaseLocal/RemoteObject, InvocationWrapper,
 * Object[], int)}: the one place every business-interface call into a session bean passes
 * (architecture/HOOKS.md). Arguments 0..3 are the generated client object, the container's
 * local/remote object, the invocation wrapper and the call arguments.
 */
public final class ContainerAdvice {

    private ContainerAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) Object invokable,
                             @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object clientObject,
                             @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) Object wrapper) {
        return EjbRecorder.enter(invokable, clientObject, wrapper);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.Argument(value = 3, typing = Assigner.Typing.DYNAMIC) Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                            @Advice.Thrown Throwable thrown) {
        EjbRecorder.exit(token, args, result, thrown);
    }
}
