package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The recording a trace belongs to and the trace's position in it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingRef {

    public String id;
    public String name;
    /** Monotonic position of the trace within the recording. */
    public long seq;
}
