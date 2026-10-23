package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Trace {

    public static final int CURRENT_FORMAT_VERSION = 1;

    public int formatVersion = CURRENT_FORMAT_VERSION;
    public RecordingRef recording;
    public TraceInfo trace;
    public List<Span> spans = new ArrayList<Span>();
    /** Written by trace-reader, never by the agent. */
    public List<LineageEntry> lineage;
}
