package io.capture.agent.hook;

import io.capture.agent.span.Recorder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into WebLogic's JDBC wrappers ({@link JdbcTargets}): {@link Execute} around the
 * {@code execute*} methods of a statement wrapper, {@link Bind} at the start of a prepared
 * statement's {@code set*} / {@code addBatch} / {@code clearParameters}, {@link Row} after a
 * result set wrapper's {@code next()}.
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
            return Recorder.enterJdbc(statement, method, args);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long token,
                                @Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                                @Advice.Thrown Throwable thrown) {
            Recorder.exitJdbc(token, statement, result, thrown);
        }
    }

    public static final class Bind {
        private Bind() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object statement,
                                 @Advice.Origin("#m") String method,
                                 @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args) {
            Recorder.bindJdbc(statement, method, args);
        }
    }

    /** The result set wrapper has no {@code next()} of its own: the generated subclass passes every call through {@code postInvocationHandler(name, args, ret)}. */
    public static final class Row {
        private Row() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This(typing = Assigner.Typing.DYNAMIC) Object resultSet,
                                 @Advice.Argument(0) String method,
                                 @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object[] args,
                                 @Advice.Argument(value = 2, typing = Assigner.Typing.DYNAMIC) Object returned) {
            Recorder.readJdbc(resultSet, method, args, returned);
        }
    }
}
