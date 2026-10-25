package io.capture.agent.hook.stubs;

/** The business interface of {@link GreeterBean}. */
public interface Greeter {

    String greet(String name);

    String fail();

    String run(String input);
}
