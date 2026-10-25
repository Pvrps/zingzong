package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.GraphSerializer;
import ca.gc.cra.xz02.devops.wltrace.agent.serialize.SerializationCeilings;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.RuntimeMBeans;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation;

/**
 * What every {@link SpanRecorder} shares: the active recording, the threads' span stacks, the
 * serializer and its ceilings, the server facts and runtime MBeans, the trace writer, the
 * WorkContext propagation and the log.
 */
public final class RecorderContext {

    final ActiveRecording active;
    final SpanStacks stacks;
    final GraphSerializer serializer;
    final SerializationCeilings ceilings;
    final ServerRuntime server;
    final RuntimeMBeans runtimeMBeans;
    final TraceWriter writer;
    final WorkContextPropagation propagation;
    final AgentLog log;

    public RecorderContext(ActiveRecording active, SpanStacks stacks, GraphSerializer serializer, SerializationCeilings ceilings,
                           ServerRuntime server, RuntimeMBeans runtimeMBeans, TraceWriter writer, WorkContextPropagation propagation,
                           AgentLog log) {
        this.active = active;
        this.stacks = stacks;
        this.serializer = serializer;
        this.ceilings = ceilings;
        this.server = server;
        this.runtimeMBeans = runtimeMBeans;
        this.writer = writer;
        this.propagation = propagation;
        this.log = log;
    }
}
