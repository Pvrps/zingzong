package io.capture.agent;

import io.capture.agent.hook.EjbAnnotationHook;
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
        AgentLog.info("capture-agent " + mode + " (" + config + ") jar=" + jar
                + " jvm=" + System.getProperty("java.version")
                + " loader=" + describe(AgentBootstrap.class.getClassLoader()));

        Recorder.configure(config);
        RecordingControl.register();

        if (AgentConfig.HOOK_CONTAINER.equals(config.hook)) {
            AgentLog.warn("hook=container is not implemented yet (Phase 6); using the annotation hook");
        }
        EjbAnnotationHook.install(inst, config);

        if (config.enabled) {
            Recorder.start("startup");
        }
        AgentLog.info("capture-agent ready, recording " + (config.enabled ? "ON" : "off"));
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.toString();
    }
}
