package io.capture.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Entry point. Keep this class free of references to the rest of the agent until the jar
 * is on the bootstrap search path: this class is loaded by the system class loader, but
 * everything it touches afterwards must resolve through the bootstrap loader so that
 * advice inlined into application classes finds the same {@code Recorder}.
 */
public final class Agent {

    private Agent() {
    }

    public static void premain(String args, Instrumentation inst) {
        install(args, inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        install(args, inst, "agentmain");
    }

    private static void install(String args, Instrumentation inst, String mode) {
        try {
            File jar = agentJar();
            if (jar != null) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            }
            AgentBootstrap.start(args, inst, mode, jar);
        } catch (Throwable t) {
            System.err.println("[capture-agent] failed to start, the server continues without capture: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static File agentJar() throws Exception {
        URL location = Agent.class.getProtectionDomain().getCodeSource().getLocation();
        File file = new File(location.toURI());
        return file.isFile() ? file : null;
    }
}
