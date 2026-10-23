package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** {@code lineage.json}: written by trace-reader into the recording directory, never by the agent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageFile {

    public int formatVersion = Trace.CURRENT_FORMAT_VERSION;
    public String recordingId;
    public String generatedAt;
    /** Sourced entries (to ← from) first, then unsourced ones with a classification. */
    public List<LineageEntry> entries = new ArrayList<LineageEntry>();
}
