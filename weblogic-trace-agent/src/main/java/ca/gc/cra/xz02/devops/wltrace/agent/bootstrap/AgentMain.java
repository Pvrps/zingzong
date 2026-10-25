package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Entry point of the {@code -javaagent}. Takes one argument, {@code out=DIR}, where agent.log and
 * the recordings go (default {@code java.io.tmpdir/weblogic-trace}), appends the agent jar to the
 * bootstrap class loader search path and hands over to {@link AgentStartup}. Nothing else of the
 * agent is referenced before that append, so that advice inlined into server classes resolves
 * the same classes as this one.
 */
public final class AgentMain {

    private AgentMain() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        install(arguments, instrumentation, "premain");
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        install(arguments, instrumentation, "agentmain");
    }

    private static void install(String arguments, Instrumentation instrumentation, String mode) {
        try {
            File jar = agentJar();
            if (jar != null) {
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar));
            }
            AgentStartup.start(outputDirectory(arguments), instrumentation, mode, jar);
        } catch (Throwable failure) {
            System.err.println("[weblogic-trace-agent] failed to start, the server continues without tracing: " + failure);
            failure.printStackTrace(System.err);
        }
    }

    /** The {@code out} argument; any other argument is refused. */
    static File outputDirectory(String arguments) {
        File outputDirectory = new File(System.getProperty("java.io.tmpdir"), "weblogic-trace");
        if (arguments == null) {
            return outputDirectory;
        }
        for (String pair : arguments.split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            String key = equals < 0 ? trimmed : trimmed.substring(0, equals).trim();
            if (!key.equals("out")) {
                throw new IllegalArgumentException("unknown agent argument: " + key + " (the only argument is out=DIR)");
            }
            outputDirectory = new File(equals < 0 ? "" : trimmed.substring(equals + 1).trim());
        }
        return outputDirectory;
    }

    private static File agentJar() throws Exception {
        URL location = AgentMain.class.getProtectionDomain().getCodeSource().getLocation();
        File file = new File(location.toURI());
        return file.isFile() ? file : null;
    }
}
