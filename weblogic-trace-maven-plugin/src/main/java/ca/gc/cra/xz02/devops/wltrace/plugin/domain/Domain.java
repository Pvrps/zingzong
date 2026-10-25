package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A WebLogic domain home and the files the plugin reads or writes in it. The domain's platform
 * follows {@code bin/}: {@code setDomainEnv.sh} means Unix, {@code setDomainEnv.cmd} means
 * Windows; with both or neither present, the platform of the JVM running the plugin.
 */
public final class Domain {

    public static final String INSTALL_DIRECTORY = "weblogic-trace";
    public static final String AGENT_JAR = "weblogic-trace-agent.jar";

    public final Path home;
    private final DomainPlatform platform;

    public Domain(Path home) {
        this.home = canonical(home);
        this.platform = detectPlatform(this.home.resolve("bin"));
    }

    /** Fails with a plain explanation when this is not a WebLogic domain. */
    public void verify() throws IOException {
        if (!Files.isDirectory(home)) {
            throw new IOException(home + " is not a directory");
        }
        if (!Files.isRegularFile(configXml())) {
            throw new IOException(home + " is not a WebLogic domain: no config/config.xml");
        }
    }

    public DomainPlatform platform() {
        return platform;
    }

    public Path configXml() {
        return home.resolve("config").resolve("config.xml");
    }

    public Path binDirectory() {
        return home.resolve("bin");
    }

    public Path overridesFile() {
        return binDirectory().resolve(platform.overridesFileName());
    }

    public Path domainEnvFile() {
        return binDirectory().resolve(platform.domainEnvFileName());
    }

    public Path installDirectory() {
        return home.resolve(INSTALL_DIRECTORY);
    }

    public Path installedAgentJar() {
        return installDirectory().resolve(AGENT_JAR);
    }

    public Path nodeManagerProperties() {
        return home.resolve("nodemanager").resolve("nodemanager.properties");
    }

    /** {@code <domain-version>} from config.xml, or {@code unknown}. */
    public String webLogicVersion() {
        try {
            String xml = new String(Files.readAllBytes(configXml()), StandardCharsets.UTF_8);
            Matcher version = Pattern.compile("<domain-version>\\s*([^<\\s]+)\\s*</domain-version>").matcher(xml);
            return version.find() ? version.group(1) : "unknown";
        } catch (IOException failure) {
            return "unknown";
        }
    }

    /** True when {@code other} names this domain home after normalisation, under the platform's case rule. */
    public boolean isHome(Path other) {
        return platform.sameLocation(home, canonical(other));
    }

    /** The real path when it exists, else the absolute normalised one. */
    public static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failure) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static DomainPlatform detectPlatform(Path binDirectory) {
        UnixPlatform unix = new UnixPlatform();
        WindowsPlatform windows = new WindowsPlatform();
        boolean unixScripts = Files.isRegularFile(binDirectory.resolve(unix.domainEnvFileName()));
        boolean windowsScripts = Files.isRegularFile(binDirectory.resolve(windows.domainEnvFileName()));
        if (unixScripts && !windowsScripts) {
            return unix;
        }
        if (windowsScripts && !unixScripts) {
            return windows;
        }
        return DomainPlatform.current();
    }

    @Override
    public String toString() {
        return home.toString();
    }
}
