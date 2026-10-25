package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.EjbRecorder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Every business-interface call into a session bean, stateless, stateful, singleton or EJB 2.x,
 * passes the static five-argument {@code invoke} of {@code SessionLocalMethodInvoker} (local
 * view) or {@code SessionRemoteMethodInvoker} (remote view). {@link SessionInvokerAdvice} is
 * inlined into both.
 */
public final class SessionInvokerHook extends Hook {

    public static final String LOCAL_INVOKER = "weblogic.ejb.container.internal.SessionLocalMethodInvoker";
    public static final String REMOTE_INVOKER = "weblogic.ejb.container.internal.SessionRemoteMethodInvoker";

    public SessionInvokerHook(EjbRecorder recorder) {
        super("session invokers", recorder);
    }

    @Override
    public List<String> targetClassNames() {
        return Arrays.asList(LOCAL_INVOKER, REMOTE_INVOKER);
    }

    @Override
    public List<AdviceBinding> bindings() {
        return Collections.singletonList(new AdviceBinding(
                TypeMatcher.named(LOCAL_INVOKER, REMOTE_INVOKER),
                named("invoke").and(isStatic()).and(takesArguments(5)),
                SessionInvokerAdvice.class));
    }
}
