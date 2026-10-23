package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code recording.json}: written by the agent at start, completed at stop. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingManifest {

    public int formatVersion = Trace.CURRENT_FORMAT_VERSION;
    public String id;
    public String name;
    /** ISO-8601 instants, UTC. */
    public String startedAt;
    public String endedAt;
    public String serverName;
    public Agent agent;
    public Filters filters;
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

    /** Optional, for shared servers: only traces matching these are recorded. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Filters {
        public String principal;
        public String httpSessionId;
    }
}
