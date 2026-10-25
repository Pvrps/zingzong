package io.capture.agent.hook.stubs;

/** What the invoker stubs do with their wrapper argument: run the bean method. */
public interface Invocation {

    Object call(Object[] args) throws Throwable;
}
