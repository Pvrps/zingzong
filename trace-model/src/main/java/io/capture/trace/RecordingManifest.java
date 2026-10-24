package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A server's manifest of a recording ({@code recording-<serverName>.json}), written by the
 * agent at start and completed at stop. trace-reader merges the servers' manifests of one
 * recording into a single view ({@link #servers}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingManifest {

    public int formatVersion = Trace.CURRENT_FORMAT_VERSION;
    public String id;
    public String name;
    /** ISO-8601 instants, UTC. */
    public String startedAt;
    public String endedAt;
    public String serverName;
    /** Set by trace-reader when merging several servers' manifests; null in a file the agent wrote. */
    public List<String> servers;
    public Agent agent;
    /** Set at stop. */
    public Long traceCount;
    public Long droppedTraces;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Agent {
        public String version;
        public String build;
        public String hook;
        public String jvm;
    }
}
