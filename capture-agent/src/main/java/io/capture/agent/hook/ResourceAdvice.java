package io.capture.agent.hook;

import io.capture.agent.span.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into public instance methods of JAX-RS resource classes ({@code javax.ws.rs.Path})
 * and JAX-WS endpoints ({@code javax.jws.WebService}): a child span of kind HTTP with
 * layer "jaxrs" or "jaxws", arguments and result captured like an EJB call. The layer is
 * decided from the class's annotations by name at the Recorder.
 */
public final class ResourceAdvice {

    private ResourceAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long enter(@Advice.This(optional = true) Object self,
                             @Advice.Origin("#t") String resourceClass,
                             @Advice.Origin("#m") String method,
                             @Advice.Origin("#s") String signature) {
        return Recorder.enterResource(self, resourceClass, method, signature);
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
        Recorder.exit(token, self, resourceClass, method, descriptor, args, result, thrown);
    }
}
