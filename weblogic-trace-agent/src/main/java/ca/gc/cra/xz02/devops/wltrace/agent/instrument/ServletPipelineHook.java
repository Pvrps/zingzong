package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.ServletRecorder;

import java.util.Arrays;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * The servlet pipeline's two entries that take the servlet API interfaces: {@code FilterChainImpl.doFilter}
 * (a web application with filters) and {@code ServletStubImpl.execute} (one without). Wrappers
 * substituted here are what every filter and the servlet see; {@link ServletPipelineAdvice} is
 * inlined into both, and a request already being recorded is left alone.
 */
public final class ServletPipelineHook extends Hook {

    public static final String FILTER_CHAIN = "weblogic.servlet.internal.FilterChainImpl";
    public static final String SERVLET_STUB = "weblogic.servlet.internal.ServletStubImpl";

    public ServletPipelineHook(ServletRecorder recorder) {
        super("servlet pipeline", recorder);
    }

    @Override
    public List<String> targetClassNames() {
        return Arrays.asList(FILTER_CHAIN, SERVLET_STUB);
    }

    @Override
    public List<AdviceBinding> bindings() {
        return Arrays.asList(
                new AdviceBinding(TypeMatcher.named(FILTER_CHAIN), named("doFilter").and(takesArguments(2)), ServletPipelineAdvice.class),
                new AdviceBinding(TypeMatcher.named(SERVLET_STUB), named("execute").and(takesArguments(2)), ServletPipelineAdvice.class));
    }
}
