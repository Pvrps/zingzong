package ca.gc.cra.xz02.devops.wltrace.agent.record;

/** The JMX contract at {@code ca.gc.cra.xz02.devops.wltrace:type=Control}, in standard MBean naming. */
public interface RecordingControlMBean {

    /** Begins a recording under the id the caller assigned (one recording has one id across every server of a domain) and returns it. */
    String start(String recordingId, String name);

    /** Ends the active recording; returns its id, or null when none was active. */
    String stop();

    /** {@code ready}, {@code recording <id>}, or {@code inert: <reason>} when the agent found no WebLogic and installs nothing. */
    String getState();

    boolean isEnabled();

    String getRecordingId();

    String getRecordingName();

    long getTraceCount();

    /** Traces discarded because the writer queue was full. Non-zero means data loss. */
    long getDroppedTraces();

    long getWrittenTraces();

    int getQueuedTraces();

    /** The agent's build stamp, to tell a loaded agent from the one installed on disk. */
    String getAgentBuild();

    String getServerName();

    /** Where this agent writes recordings. */
    String getOutputDirectory();
}
