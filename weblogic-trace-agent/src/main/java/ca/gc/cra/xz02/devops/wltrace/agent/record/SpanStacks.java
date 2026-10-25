package ca.gc.cra.xz02.devops.wltrace.agent.record;

/** The {@link SpanStack} of each thread, created on first use. */
public final class SpanStacks {

    private final ThreadLocal<SpanStack> stacks = ThreadLocal.withInitial(SpanStack::new);

    SpanStack current() {
        return stacks.get();
    }
}
