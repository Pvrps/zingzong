package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.ResourceRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into the public instance methods of JAX-RS resource classes ({@code javax.ws.rs.Path})
 * and JAX-WS endpoints ({@code javax.jws.WebService}) defined by an application loader. The
 * origin strings are compile-time constants per call site; the recorder resolves the method
 * from them.
 */
public final class ResourceAdvice {

    private ResourceAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This(optional = true) Object self,
                             @Advice.Origin("#t") String resourceClass,
                             @Advice.Origin("#m") String method) {
        return ResourceRecorder.enter(self, resourceClass, method);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter long token,
                            @Advice.This(optional = true) Object self,
                            @Advice.Origin("#t") String resourceClass,
                            @Advice.Origin("#m") String method,
                            @Advice.Origin("#d") String descriptor,
                            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args,
                            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                            @Advice.Thrown Throwable thrown) {
        ResourceRecorder.exit(token, self, resourceClass, method, descriptor, args, result, thrown);
    }
}
