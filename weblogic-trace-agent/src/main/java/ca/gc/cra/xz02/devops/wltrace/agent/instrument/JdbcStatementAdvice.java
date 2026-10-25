package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into WebLogic's JDBC wrappers: {@link Execute} around a statement wrapper's
 * {@code execute*} methods, {@link Bind} at the start of a prepared statement's
 * {@code set*}, {@code addBatch} and {@code clearParameters}, {@link RowRead} after each call
 * the result-set wrapper routes through {@code postInvocationHandler(name, arguments, returned)}.
 * No lambdas: this code is copied into the target methods.
 */
public final class JdbcStatementAdvice {

    private JdbcStatementAdvice() {
    }

    public static final class Execute {

        private Execute() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                 @Advice.Origin("#m") String operation,
                                 @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] arguments) {
            return TraceRuntime.jdbc().enter(statement, operation, arguments);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long token,
                                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                                @Advice.Thrown Throwable thrown) {
            TraceRuntime.jdbc().exit(token, null, result, thrown);
        }
    }

    public static final class Bind {

        private Bind() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                 @Advice.Origin("#m") String method,
                                 @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] arguments) {
            TraceRuntime.jdbc().bind(statement, method, arguments);
        }
    }

    /** The result-set wrapper defines no {@code next()} itself; the generated subclass passes every call through the handler. */
    public static final class RowRead {

        private RowRead() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object resultSet,
                                 @Advice.Argument(0) String method,
                                 @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) Object returned) {
            TraceRuntime.jdbc().rowRead(resultSet, method, returned);
        }
    }
}
