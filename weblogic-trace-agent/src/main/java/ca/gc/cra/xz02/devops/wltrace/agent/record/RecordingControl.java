package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentVersion;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/** The control MBean on the platform MBeanServer: start, stop and status of recordings, for the plugin over JMX. */
public final class RecordingControl implements RecordingControlMBean {

    public static final String OBJECT_NAME = "ca.gc.cra.xz02.devops.wltrace:type=Control";

    private final RecordingLifecycle lifecycle;
    private final TraceWriter writer;
    private final ServerRuntime server;
    private final AgentLog log;

    public RecordingControl(RecordingLifecycle lifecycle, TraceWriter writer, ServerRuntime server, AgentLog log) {
        this.lifecycle = lifecycle;
        this.writer = writer;
        this.server = server;
        this.log = log;
    }

    /** Registers this MBean, replacing an earlier registration under the same name; a failure is logged, not thrown. */
    public void register() {
        try {
            MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (platform.isRegistered(name)) {
                platform.unregisterMBean(name);
            }
            platform.registerMBean(this, name);
            log.info("registered JMX MBean " + OBJECT_NAME);
        } catch (Throwable failure) {
            log.error("could not register " + OBJECT_NAME + "; recordings cannot be started on this server", failure);
        }
    }

    @Override
    public String start(String recordingId, String name) {
        return lifecycle.start(recordingId, name).id;
    }

    @Override
    public String stop() {
        return lifecycle.stop().map(recording -> recording.id).orElse(null);
    }

    @Override
    public String getState() {
        return lifecycle.state();
    }

    @Override
    public boolean isEnabled() {
        return lifecycle.active().isPresent();
    }

    @Override
    public String getRecordingId() {
        return lifecycle.active().map(recording -> recording.id).orElse(null);
    }

    @Override
    public String getRecordingName() {
        return lifecycle.active().map(recording -> recording.name).orElse(null);
    }

    @Override
    public long getTraceCount() {
        return lifecycle.active().map(Recording::traceCount).orElse(0L);
    }

    @Override
    public long getDroppedTraces() {
        return writer.dropped();
    }

    @Override
    public long getWrittenTraces() {
        return writer.written();
    }

    @Override
    public int getQueuedTraces() {
        return writer.queued();
    }

    @Override
    public String getAgentBuild() {
        return AgentVersion.BUILD;
    }

    @Override
    public String getServerName() {
        return server.serverName();
    }

    @Override
    public String getOutputDirectory() {
        return writer.outputDirectory().getAbsolutePath();
    }
}
