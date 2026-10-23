package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingRef {

    public String id;
    public String name;
    /** Monotonic position of the trace within the recording. */
    public long seq;
}
