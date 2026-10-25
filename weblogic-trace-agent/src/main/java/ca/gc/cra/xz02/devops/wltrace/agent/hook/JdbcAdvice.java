package ca.gc.cra.xz02.devops.wltrace.agent.hook;

import ca.gc.cra.xz02.devops.wltrace.agent.span.JdbcRecorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into WebLogic's JDBC wrappers ({@link JdbcTargets}): {@link Execute} around the
 * {@code execute*} methods of a statement wrapper, {@link Bind} at the start of a prepared
 * statement's {@code set*} / {@code addBatch} / {@code clearParameters}, {@link Next} after
 * each call the result-set wrapper routes through its handler, to count the rows read.
 */
public final class JdbcAdvice {

    private JdbcAdvice() {
    }

    public static final class Execute {
        private Execute() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                 @Advice.Origin("#m") String method,
                                 @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            return JdbcRecorder.enter(statement, method, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long token,
                                @Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                                @Advice.Thrown Throwable thrown) {
            JdbcRecorder.exit(token, statement, result, thrown);
        }
    }

    public static final class Bind {
        private Bind() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                 @Advice.Origin("#m") String method,
                                 @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            JdbcRecorder.bind(statement, method, args);
        }
    }

    /** The result-set wrapper defines no {@code next()} itself; the generated subclass passes every call through {@code postInvocationHandler(name, args, returned)}. */
    public static final class Next {
        private Next() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object resultSet,
                                 @Advice.Argument(0) String method,
                                 @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) Object returned) {
            JdbcRecorder.rowRead(resultSet, method, returned);
        }
    }
}
