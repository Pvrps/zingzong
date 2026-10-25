package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.record.MessageRecorder;

import java.util.Collections;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Every JMS message reaches a message-driven bean through {@code MDListener.onMessage(Message)}; {@link MessageListenerAdvice} is inlined there. */
public final class MessageListenerHook extends Hook {

    public static final String MESSAGE_LISTENER = "weblogic.ejb.container.internal.MDListener";

    public MessageListenerHook(MessageRecorder recorder) {
        super("message-driven listener", recorder);
    }

    @Override
    public List<String> targetClassNames() {
        return Collections.singletonList(MESSAGE_LISTENER);
    }

    @Override
    public List<AdviceBinding> bindings() {
        return Collections.singletonList(new AdviceBinding(
                TypeMatcher.named(MESSAGE_LISTENER),
                named("onMessage").and(not(isStatic())).and(takesArguments(1)),
                MessageListenerAdvice.class));
    }
}
