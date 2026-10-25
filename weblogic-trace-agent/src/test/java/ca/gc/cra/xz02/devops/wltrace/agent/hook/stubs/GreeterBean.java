package ca.gc.cra.xz02.devops.wltrace.agent.hook.stubs;

/** A session bean; {@link #run} calls another bean through the container, like a real nested EJB call. */
public class GreeterBean implements Greeter {

    @Override
    public String greet(String name) {
        return "hello " + name;
    }

    @Override
    public String fail() {
        throw new IllegalStateException("greeter exploded");
    }

    @Override
    public String run(String input) {
        String inner = Calls.local(new GreeterBean(), "greet", new Object[]{input});
        return inner + "!";
    }
}
