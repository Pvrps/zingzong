package ca.gc.cra.xz02.devops.wltrace.agent.record;

/**
 * The agent as one object: the recording lifecycle, the control MBean and the five recorders the
 * advice reports to. Built once by the bootstrap and published through {@link TraceRuntime}.
 */
public final class TraceAgent {

    private final RecordingLifecycle lifecycle;
    private final RecordingControl control;
    private final EjbRecorder ejb;
    private final MessageRecorder message;
    private final ResourceRecorder resource;
    private final ServletRecorder servlet;
    private final JdbcRecorder jdbc;

    public TraceAgent(RecordingLifecycle lifecycle, RecordingControl control, EjbRecorder ejb, MessageRecorder message,
                      ResourceRecorder resource, ServletRecorder servlet, JdbcRecorder jdbc) {
        this.lifecycle = lifecycle;
        this.control = control;
        this.ejb = ejb;
        this.message = message;
        this.resource = resource;
        this.servlet = servlet;
        this.jdbc = jdbc;
    }

    public RecordingLifecycle lifecycle() {
        return lifecycle;
    }

    public RecordingControl control() {
        return control;
    }

    public EjbRecorder ejb() {
        return ejb;
    }

    public MessageRecorder message() {
        return message;
    }

    public ResourceRecorder resource() {
        return resource;
    }

    public ServletRecorder servlet() {
        return servlet;
    }

    public JdbcRecorder jdbc() {
        return jdbc;
    }
}
