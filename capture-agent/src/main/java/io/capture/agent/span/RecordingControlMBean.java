package io.capture.agent.span;

/** JMX contract at {@code io.capture:type=Control}. Standard MBean naming. */
public interface RecordingControlMBean {

    /** Begins a recording window and returns its id. */
    String start(String name);

    /** Ends the active recording; returns its id or null if none was active. */
    String stop();

    boolean isEnabled();

    String getRecordingId();

    String getRecordingName();

    long getTraceCount();

    /** Traces discarded because the writer queue was full. Non-zero means data loss. */
    long getDroppedTraces();

    long getWrittenTraces();

    int getQueuedTraces();
}
