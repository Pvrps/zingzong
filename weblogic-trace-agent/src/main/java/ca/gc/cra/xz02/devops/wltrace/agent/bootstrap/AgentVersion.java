package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentVersion;

import java.io.InputStream;
import java.util.Properties;

/** Version and build stamp from build.properties (filtered by Maven). */
public final class AgentVersion {

    public static final String VERSION;
    public static final String BUILD;

    static {
        Properties p = new Properties();
        try {
            InputStream in = AgentVersion.class.getResourceAsStream("build.properties");
            if (in != null) {
                try {
                    p.load(in);
                } finally {
                    in.close();
                }
            }
        } catch (Throwable ignored) {
            // leave defaults
        }
        VERSION = p.getProperty("version", "unknown");
        BUILD = p.getProperty("build", "unknown");
    }

    private AgentVersion() {
    }
}
