package io.capture.agent.span;

/** JMX contract at {@code io.capture:type=Control}. Standard MBean naming. */
public interface RecordingControlMBean {

    /**
     * Begins a recording window under the given id (assigned by the caller: one capture has
     * one id across every server of a domain) and returns it.
     */
    String start(String recordingId, String name);

    /** Ends the active recording; returns its id or null if none was active. */
    String stop();

    /** "ready", "recording <id>", or "inert: <reason>" when the agent found no WebLogic and installs nothing. */
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
