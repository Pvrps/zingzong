package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** The viewer index of one recording: merged manifest, one summary per trace, lineage entries. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingIndex {

    public int formatVersion = Trace.CURRENT_FORMAT_VERSION;
    public RecordingManifest recording;
    public List<TraceSummary> traces = new ArrayList<TraceSummary>();
    public List<LineageEntry> lineage = new ArrayList<LineageEntry>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TraceSummary {
        public String id;
        public long seq;
        /** File name relative to the recording directory. */
        public String file;
        public String startedAt;
        public long durationMs;
        public String principal;
        public String httpSessionId;
        public String parentTraceId;
        public String parentSpanId;
        public String rootKind;
        /** "OrderServiceBean.createOrder" or "POST /orders/api/orders". */
        public String rootLabel;
        public int spanCount;
        public int warningCount;
        public boolean failed;
    }
}
