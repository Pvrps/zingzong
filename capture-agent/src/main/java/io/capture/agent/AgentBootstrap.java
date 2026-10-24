package io.capture.agent;

import io.capture.agent.hook.CaptureHook;
import io.capture.agent.hook.ContainerTargets;
import io.capture.agent.hook.FallbackHook;
import io.capture.agent.hook.InstrumentationHook;
import io.capture.agent.span.Recorder;
import io.capture.agent.span.RecordingControl;
import io.capture.agent.span.WlsWorkContextPropagation;

import java.io.File;
import java.lang.instrument.Instrumentation;

/**
 * Everything {@link Agent} does after the jar is on the bootstrap path. Public, not
 * package-private: {@link Agent} lives in the system loader and this class in the bootstrap
 * loader, so they are in different runtime packages.
 *
 * Nothing is instrumented here: the hook is installed by {@code Recorder.start} and removed
 * by {@code Recorder.stop}, so a loaded agent with no recording leaves every class as it was.
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
        RecordingControl.register();

        ServerDetection.Server server = ServerDetection.detect();
        ContainerTargets.Target target = ContainerTargets.resolve();
        boolean container = ServerDetection.useContainerHook(server, target != null);
        AgentLog.info("server=" + server + " hook=" + (container ? "container" : "annotation")
                + (target != null ? "; container choke points of " + target.versions + " present"
                        : "; no known container choke points on the class path"));

        CaptureHook annotation = new CaptureHook(inst, CaptureHook.EjbStrategy.ANNOTATION);
        InstrumentationHook chosen = container
                ? new FallbackHook(new CaptureHook(inst, CaptureHook.EjbStrategy.CONTAINER, target), annotation)
                : annotation;
        Recorder.hook(chosen);
        if (server == ServerDetection.Server.WEBLOGIC) {
            Recorder.propagation(WlsWorkContextPropagation.create());
        }
        AgentLog.info("capture-agent ready, recording off");
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }
}
