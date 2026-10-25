package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** One trace file: the work one thread did for one entry point, as a flat list of spans with parent ids. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Trace {

    public static final int CURRENT_FORMAT_VERSION = 1;

    public int formatVersion = CURRENT_FORMAT_VERSION;
    public RecordingRef recording;
    public TraceInfo trace;
    public List<Span> spans = new ArrayList<Span>();
    /** Set by the plugin when it indexes a recording, never by the agent. */
    public List<LineageEntry> lineage;
}
