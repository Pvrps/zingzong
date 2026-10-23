package io.capture.agent;

import io.capture.agent.hook.CaptureHook;
import io.capture.agent.hook.FallbackHook;
import io.capture.agent.hook.InstrumentationHook;
import io.capture.agent.span.Recorder;
import io.capture.agent.span.RecordingControl;

import java.io.File;
import java.lang.instrument.Instrumentation;

/**
 * Everything {@link Agent} does after the jar is on the bootstrap path. Public, not
 * package-private: {@link Agent} lives in the system loader and this class in the bootstrap
 * loader, so they are in different runtime packages.
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
        boolean containerAvailable = CaptureHook.containerTargetsPresent();
        String hook = ServerDetection.chooseHook(config.hook, server, containerAvailable);
        AgentLog.info("server=" + server + " hook=" + hook
                + (config.hook != null ? " (overridden)" : " (auto)")
                + (containerAvailable ? "; container invokers present" : "; container invokers not on the class path"));

        // Installed by Recorder.start and removed by Recorder.stop: no advice without a recording.
        CaptureHook annotation = new CaptureHook(inst, config, CaptureHook.EjbStrategy.ANNOTATION);
        InstrumentationHook chosen = annotation;
        if (AgentConfig.HOOK_CONTAINER.equals(hook)) {
            if (containerAvailable) {
                chosen = new FallbackHook(new CaptureHook(inst, config, CaptureHook.EjbStrategy.CONTAINER), annotation);
            } else {
                AgentLog.warn("hook=container requested but " + CaptureHook.CONTAINER_INVOKERS[0]
                        + " is not resolvable; using the annotation hook");
            }
        }
        Recorder.hook(chosen);

        if (config.enabled) {
            Recorder.start("startup");
        }
        AgentLog.info("capture-agent ready, recording " + (config.enabled ? "ON" : "off"));
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }
}
