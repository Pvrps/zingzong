package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** The lineage entries of one recording, as the plugin computes them. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineageFile {

    public int formatVersion = Trace.CURRENT_FORMAT_VERSION;
    public String recordingId;
    public String generatedAt;
    /** Sourced entries (to ← from) first, then unsourced ones with a classification. */
    public List<LineageEntry> entries = new ArrayList<LineageEntry>();
}
