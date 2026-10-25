package ca.gc.cra.xz02.devops.wltrace.agent.span;

import ca.gc.cra.xz02.devops.wltrace.agent.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.AgentVersion;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/** The {@code ca.gc.cra.xz02.devops.wltrace:type=Control} MBean on the platform MBeanServer: start, stop and status of recordings. */
public final class RecordingControl implements RecordingControlMBean {

    public static final String OBJECT_NAME = "ca.gc.cra.xz02.devops.wltrace:type=Control";

    public static void register() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(OBJECT_NAME);
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            server.registerMBean(new RecordingControl(), name);
            AgentLog.info("registered JMX MBean " + OBJECT_NAME);
        } catch (Throwable t) {
            AgentLog.error("could not register " + OBJECT_NAME + "; captures cannot be started on this server", t);
        }
    }

    @Override
    public String start(String recordingId, String name) {
        return Recorder.start(recordingId, name).id;
    }

    @Override
    public String stop() {
        Recording recording = Recorder.stop();
        return recording == null ? null : recording.id;
    }

    @Override
    public String getState() {
        return Recorder.state();
    }

    @Override
    public boolean isEnabled() {
        return Recorder.isEnabled();
    }

    @Override
    public String getRecordingId() {
        Recording recording = Recorder.active();
        return recording == null ? null : recording.id;
    }

    @Override
    public String getRecordingName() {
        Recording recording = Recorder.active();
        return recording == null ? null : recording.name;
    }

    @Override
    public long getTraceCount() {
        Recording recording = Recorder.active();
        return recording == null ? 0L : recording.traceCount();
    }

    @Override
    public long getDroppedTraces() {
        return Recorder.droppedTraces();
    }

    @Override
    public long getWrittenTraces() {
        return Recorder.writtenTraces();
    }

    @Override
    public int getQueuedTraces() {
        return Recorder.queuedTraces();
    }

    @Override
    public String getAgentBuild() {
        return AgentVersion.BUILD;
    }

    @Override
    public String getServerName() {
        return WlsRuntime.serverName();
    }

    @Override
    public String getOutputDirectory() {
        return Recorder.outDir() == null ? null : Recorder.outDir().getAbsolutePath();
    }
}
