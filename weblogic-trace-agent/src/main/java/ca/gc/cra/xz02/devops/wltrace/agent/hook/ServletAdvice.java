package ca.gc.cra.xz02.devops.wltrace.agent.hook;

import ca.gc.cra.xz02.devops.wltrace.agent.http.ServletRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into {@code weblogic.servlet.internal.FilterChainImpl.doFilter(ServletRequest, ServletResponse)},
 * the first point in WebLogic's pipeline that takes the servlet API interfaces and passes
 * them down to every filter and the servlet. On entry the request and response are
 * replaced by tee wrappers; on exit the span is completed.
 */
public final class ServletAdvice {

    private ServletAdvice() {
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object[] enter(@Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object request,
                                 @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object response) {
        Object[] context = ServletRecorder.enter(request, response);
        if (context != null) {
            request = context[0];
            response = context[1];
        }
        return context;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object[] context, @Advice.Thrown Throwable thrown) {
        ServletRecorder.exit(context, thrown);
    }
}
