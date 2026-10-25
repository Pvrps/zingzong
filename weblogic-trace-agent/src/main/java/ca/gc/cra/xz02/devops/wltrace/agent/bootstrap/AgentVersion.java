package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Version and build stamp of this agent, read from {@code build.properties} as Maven filtered it. */
public final class AgentVersion {

    public static final String VERSION;
    public static final String BUILD;

    static {
        Properties properties = new Properties();
        try (InputStream in = AgentVersion.class.getResourceAsStream("build.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException | RuntimeException ignored) {
            // the defaults below say the stamp is unknown
        }
        VERSION = properties.getProperty("version", "unknown");
        BUILD = properties.getProperty("build", "unknown");
    }

    private AgentVersion() {
    }
}
