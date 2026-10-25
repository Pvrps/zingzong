package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code FilterChainImpl.doFilter} and {@code ServletStubImpl.execute}
 * {@code (ServletRequest, ServletResponse)}. On entry the request and response are replaced by
 * the tee wrappers the recorder hands back; on exit the span is completed. No lambdas: this
 * code is copied into the target method.
 */
public final class ServletPipelineAdvice {

    private ServletPipelineAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object[] enter(@Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request,
                                 @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object response) {
        Object[] exchange = TraceRuntime.servlet().enterRequest(request, response);
        if (exchange != null) {
            request = exchange[0];
            response = exchange[1];
        }
        return exchange;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object[] exchange, @Advice.Thrown Throwable thrown) {
        TraceRuntime.servlet().exitRequest(exchange, thrown);
    }
}
