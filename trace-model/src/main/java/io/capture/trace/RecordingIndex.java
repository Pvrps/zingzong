package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** {@code recording-index.json}: everything the viewer needs in one file. Written by trace-reader. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingIndex {

    public int formatVersion = Trace.CURRENT_FORMAT_VERSION;
    public RecordingManifest recording;
    public List<TraceSummary> traces = new ArrayList<TraceSummary>();
    public List<Session> sessions = new ArrayList<Session>();
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

    /** A lane in the viewer: traces grouped by HTTP session, else principal, else the whole recording. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Session {
        public static final String KIND_HTTP_SESSION = "httpSession";
        public static final String KIND_PRINCIPAL = "principal";
        public static final String KIND_RECORDING = "recording";

        public String id;
        public String kind;
        public String key;
        public List<String> traceIds = new ArrayList<String>();
    }
}
