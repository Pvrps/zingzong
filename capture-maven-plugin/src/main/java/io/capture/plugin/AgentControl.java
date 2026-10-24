package io.capture.plugin;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.Closeable;
import java.io.IOException;

/** The agent's {@code io.capture:type=Control} MBean in one server JVM, over the JDK's local connector. */
public final class AgentControl implements Closeable {

    public static final String OBJECT_NAME = "io.capture:type=Control";

    /** What the MBean reports. */
    public static final class Status {
        public final boolean recording;
        public final String recordingId;
        public final String recordingName;
        public final long traceCount;
        public final long droppedTraces;
        public final String agentBuild;
        public final String serverName;
        public final String outputDirectory;

        Status(boolean recording, String recordingId, String recordingName, long traceCount, long droppedTraces,
               String agentBuild, String serverName, String outputDirectory) {
            this.recording = recording;
            this.recordingId = recordingId;
            this.recordingName = recordingName;
            this.traceCount = traceCount;
            this.droppedTraces = droppedTraces;
            this.agentBuild = agentBuild;
            this.serverName = serverName;
            this.outputDirectory = outputDirectory;
        }
    }

    private final JMXConnector connector;
    private final MBeanServerConnection server;
    private final ObjectName name;

    private AgentControl(JMXConnector connector) throws Exception {
        this.connector = connector;
        this.server = connector.getMBeanServerConnection();
        this.name = new ObjectName(OBJECT_NAME);
    }

    public static AgentControl connect(String localConnectorAddress) throws Exception {
        return new AgentControl(JMXConnectorFactory.connect(new JMXServiceURL(localConnectorAddress)));
    }

    /** Whether the agent's MBean is registered in this JVM. */
    public boolean agentPresent() throws IOException {
        return server.isRegistered(name);
    }

    public Status status() throws Exception {
        return new Status(
                Boolean.TRUE.equals(server.getAttribute(name, "Enabled")),
                (String) server.getAttribute(name, "RecordingId"),
                (String) server.getAttribute(name, "RecordingName"),
                ((Number) server.getAttribute(name, "TraceCount")).longValue(),
                ((Number) server.getAttribute(name, "DroppedTraces")).longValue(),
                (String) server.getAttribute(name, "AgentBuild"),
                (String) server.getAttribute(name, "ServerName"),
                (String) server.getAttribute(name, "OutputDirectory"));
    }

    public String start(String recordingId, String recordingName) throws Exception {
        return (String) server.invoke(name, "start", new Object[]{recordingId, recordingName},
                new String[]{String.class.getName(), String.class.getName()});
    }

    public String stop() throws Exception {
        return (String) server.invoke(name, "stop", new Object[0], new String[0]);
    }

    @Override
    public void close() {
        try {
            connector.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
