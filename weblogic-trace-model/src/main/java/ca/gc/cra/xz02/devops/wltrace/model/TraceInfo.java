package ca.gc.cra.xz02.devops.wltrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Identity and context of a trace: when it started, who ran it, on which server, and what caused it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceInfo {

    public String id;
    /** ISO-8601 instant, UTC. */
    public String startedAt;
    public long durationMs;
    public String principal;
    public String httpSessionId;
    public String serverName;
    /** Set when the trace was started by a span carrying a foreign trace id (cross-server). */
    public String parentTraceId;
    /** The span of that trace that was active when the handoff happened. */
    public String parentSpanId;
    public List<Deployment> deployments;

    /** An application deployed on the server, from the domain configuration: where its EAR/WAR lives. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Deployment {
        public String name;
        public String sourcePath;
        public String moduleType;
    }
}
