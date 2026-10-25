package ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs;

/** The business interface of {@link GreeterBean}. */
public interface Greeter {

    String greet(String name);

    String fail();

    String run(String input);
}
