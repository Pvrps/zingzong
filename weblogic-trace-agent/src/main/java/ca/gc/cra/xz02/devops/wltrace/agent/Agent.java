package ca.gc.cra.xz02.devops.wltrace.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Entry point of the {@code -javaagent}. Takes one argument, {@code out=DIR}, where agent.log and
 * the recordings go (default {@code java.io.tmpdir/capture}), puts the jar on the bootstrap search
 * path and hands over to {@link AgentBootstrap}. Nothing else of the agent is referenced before
 * that, so that advice inlined into server classes resolves the same classes as this one.
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
            AgentBootstrap.start(outDir(args), inst, mode, jar);
        } catch (Throwable t) {
            System.err.println("[weblogic-trace-agent] failed to start, the server continues without capture: " + t);
            t.printStackTrace(System.err);
        }
    }

    /** The {@code out} argument; anything else is refused. */
    static File outDir(String args) {
        File outDir = new File(System.getProperty("java.io.tmpdir"), "capture");
        if (args != null) {
            for (String pair : args.split(",")) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String key = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
                if (!key.equals("out")) {
                    throw new IllegalArgumentException("unknown agent argument: " + key + " (the only argument is out=DIR)");
                }
                outDir = new File(eq < 0 ? "" : trimmed.substring(eq + 1).trim());
            }
        }
        return outDir;
    }

    private static File agentJar() throws Exception {
        URL location = Agent.class.getProtectionDomain().getCodeSource().getLocation();
        File file = new File(location.toURI());
        return file.isFile() ? file : null;
    }
}
