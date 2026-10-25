package io.capture.agent;

import io.capture.agent.hook.CaptureHook;
import io.capture.agent.hook.ContainerTargets;
import io.capture.agent.span.Recorder;
import io.capture.agent.span.RecordingControl;
import io.capture.agent.span.WorkContextPropagation;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.List;

/**
 * Everything {@link Agent} does after the jar is on the bootstrap path: logging, the control
 * MBean, and the hook when this JVM is a WebLogic server with the expected container classes.
 * Anywhere else the agent logs one line and stays inert. Public because {@link Agent} lives in
 * the system loader and this class in the bootstrap loader.
 */
public final class AgentBootstrap {

    private AgentBootstrap() {
    }

    public static void start(String args, Instrumentation inst, String mode, File jar) {
        AgentConfig config = AgentConfig.parse(args);
        AgentLog.init(config.outDir);
        AgentLog.info("capture-agent " + AgentVersion.VERSION + " (" + AgentVersion.BUILD + ") " + mode
                + " (" + config + ") jar=" + jar
                + " jvm=" + System.getProperty("java.version")
                + " loader=" + describe(AgentBootstrap.class.getClassLoader()));

        Recorder.configure(config);
        String inert = inertReason();
        if (inert != null) {
            Recorder.inert(inert);
            RecordingControl.register();
            AgentLog.info("capture-agent inert: " + inert);
            return;
        }
        Recorder.hook(new CaptureHook(inst), WorkContextPropagation.create());
        RecordingControl.register();
        AgentLog.info("capture-agent ready, recording off");
    }

    /** Why the agent must stay inert in this JVM, or null when it is a WebLogic server with the expected classes. */
    static String inertReason() {
        boolean weblogic = System.getProperty("weblogic.home") != null
                || ClassLoader.getSystemResource("weblogic/Server.class") != null;
        if (!weblogic) {
            return "not a WebLogic server (no weblogic.home property, no weblogic.Server class)";
        }
        List<String> missing = ContainerTargets.missing();
        return missing.isEmpty() ? null : "WebLogic without the expected container classes " + missing;
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }
}
