package io.capture.agent.control;

import io.capture.agent.span.RecordingControl;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Tiny command-line JMX client for {@link RecordingControl}, driven by scripts/capture.sh:
 * <pre>java -cp capture-agent.jar io.capture.agent.control.ControlClient URL start NAME | stop | status</pre>
 * The URL is a JMX service URL, typically the local connector address printed by
 * {@code jcmd PID ManagementAgent.status}.
 */
public final class ControlClient {

    private ControlClient() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ControlClient <jmx-url> start <name> | stop | status");
            System.exit(2);
        }
        String url = args[0];
        String command = args[1];

        JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(url));
        try {
            MBeanServerConnection server = connector.getMBeanServerConnection();
            ObjectName control = new ObjectName(RecordingControl.OBJECT_NAME);
            if (!server.isRegistered(control)) {
                System.err.println("MBean " + RecordingControl.OBJECT_NAME
                        + " is not registered; was the server started with the capture agent?");
                System.exit(3);
            }
            if (command.equals("start")) {
                String name = args.length > 2 ? args[2] : "unnamed";
                Object id = server.invoke(control, "start", new Object[]{name}, new String[]{String.class.getName()});
                System.out.println("recording started: " + id + " (" + name + ")");
            } else if (command.equals("stop")) {
                Object id = server.invoke(control, "stop", new Object[0], new String[0]);
                System.out.println(id == null ? "no recording was active" : "recording stopped: " + id
                        + " written=" + server.getAttribute(control, "WrittenTraces")
                        + " dropped=" + server.getAttribute(control, "DroppedTraces"));
            } else if (command.equals("status")) {
                boolean enabled = (Boolean) server.getAttribute(control, "Enabled");
                String counters = " written=" + server.getAttribute(control, "WrittenTraces")
                        + " queued=" + server.getAttribute(control, "QueuedTraces")
                        + " dropped=" + server.getAttribute(control, "DroppedTraces");
                if (enabled) {
                    System.out.println("recording ON: " + server.getAttribute(control, "RecordingId")
                            + " (" + server.getAttribute(control, "RecordingName") + "), traces="
                            + server.getAttribute(control, "TraceCount") + counters);
                } else {
                    System.out.println("recording off" + counters);
                }
            } else {
                System.err.println("unknown command: " + command);
                System.exit(2);
            }
        } finally {
            connector.close();
        }
    }
}
