package ca.gc.cra.xz02.devops.wltrace.agent.record;

/**
 * The one static edge of the agent: the advice inlined into server classes has no instance to
 * call, so it reaches the installed {@link TraceAgent} here. Everything behind this class is an
 * ordinary object. The agent is installed once at startup, before any hook is installed, so the
 * recorders are never null when advice runs.
 */
public final class TraceRuntime {

    private static volatile TraceAgent agent;

    private TraceRuntime() {
    }

    /** Publishes the agent to the advice; a test installs its own and reinstalls on teardown. */
    public static void install(TraceAgent instance) {
        agent = instance;
    }

    public static TraceAgent agent() {
        return agent;
    }

    public static EjbRecorder ejb() {
        return agent.ejb();
    }

    public static MessageRecorder message() {
        return agent.message();
    }

    public static ResourceRecorder resource() {
        return agent.resource();
    }

    public static ServletRecorder servlet() {
        return agent.servlet();
    }

    public static JdbcRecorder jdbc() {
        return agent.jdbc();
    }
}
