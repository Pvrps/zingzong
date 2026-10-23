package io.capture.agent.span;

import io.capture.agent.AgentLog;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

public final class RecordingControl implements RecordingControlMBean {

    public static final String OBJECT_NAME = "io.capture:type=Control";

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
            AgentLog.error("could not register " + OBJECT_NAME + "; capture-start will not work", t);
        }
    }

    @Override
    public String start(String name) {
        return Recorder.start(name).id;
    }

    @Override
    public String stop() {
        Recording recording = Recorder.stop();
        return recording == null ? null : recording.id;
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
}
