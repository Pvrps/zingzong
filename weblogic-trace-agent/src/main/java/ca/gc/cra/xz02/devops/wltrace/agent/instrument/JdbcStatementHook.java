package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.JdbcRecorder;

import java.util.Arrays;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Every statement a data source hands out is one of WebLogic's wrappers around the driver's
 * object, and every result set a {@code weblogic.jdbc.wrapper.ResultSet}. Three bindings:
 * {@link JdbcStatementAdvice.Execute} around the statement wrappers' {@code execute*},
 * {@link JdbcStatementAdvice.Bind} at the start of their {@code set*(index, value)},
 * {@code addBatch} and {@code clearParameters}, and {@link JdbcStatementAdvice.RowRead} after each
 * call the result-set wrapper routes through its handler.
 */
public final class JdbcStatementHook extends Hook {

    public static final String STATEMENT = "weblogic.jdbc.wrapper.Statement";
    public static final String PREPARED_STATEMENT = "weblogic.jdbc.wrapper.PreparedStatement";
    public static final String CALLABLE_STATEMENT = "weblogic.jdbc.wrapper.CallableStatement";
    public static final String RESULT_SET = "weblogic.jdbc.wrapper.ResultSet";

    public JdbcStatementHook(JdbcRecorder recorder) {
        super("JDBC wrappers", recorder);
    }

    @Override
    public List<String> targetClassNames() {
        return Arrays.asList(STATEMENT, PREPARED_STATEMENT, CALLABLE_STATEMENT, RESULT_SET);
    }

    @Override
    public List<AdviceBinding> bindings() {
        TypeMatcher statements = TypeMatcher.named(STATEMENT, PREPARED_STATEMENT, CALLABLE_STATEMENT);
        return Arrays.asList(
                new AdviceBinding(statements,
                        isPublic().and(not(isStatic())).and(nameStartsWith("execute")),
                        JdbcStatementAdvice.Execute.class),
                new AdviceBinding(statements,
                        isPublic().and(not(isStatic())).and(
                                nameStartsWith("set").and(takesArgument(0, int.class)).and(not(takesArguments(1)))
                                        .or(named("addBatch")).or(named("clearParameters"))),
                        JdbcStatementAdvice.Bind.class),
                new AdviceBinding(TypeMatcher.named(RESULT_SET),
                        named("postInvocationHandler").and(takesArguments(3)),
                        JdbcStatementAdvice.RowRead.class));
    }
}
