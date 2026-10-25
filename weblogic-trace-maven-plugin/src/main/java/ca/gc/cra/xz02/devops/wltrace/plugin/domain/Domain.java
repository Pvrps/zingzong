package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A WebLogic domain home and the files the plugin reads or writes in it. The domain's platform
 * follows {@code bin/}: {@code setDomainEnv.sh} means Unix, {@code setDomainEnv.cmd} means
 * Windows; with both present the platform of the current OS wins.
 */
public final class Domain {

    /** Which start scripts the domain has, and therefore which overrides file and syntax the plugin uses. */
    public enum Platform {
        UNIX("setDomainEnv.sh", "setUserOverrides.sh"),
        WINDOWS("setDomainEnv.cmd", "setUserOverrides.cmd");

        public final String domainEnv;
        public final String userOverrides;

        Platform(String domainEnv, String userOverrides) {
            this.domainEnv = domainEnv;
            this.userOverrides = userOverrides;
        }

        /** The platform of the JVM the plugin runs in. */
        public static Platform current() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? WINDOWS : UNIX;
        }
    }

    public static final String INSTALL_DIR = "weblogic-trace";
    public static final String AGENT_JAR = "weblogic-trace-agent.jar";

    public final Path home;

    public Domain(Path home) {
        this.home = canonical(home);
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

    public Path configXml() {
        return home.resolve("config").resolve("config.xml");
    }

    public Path binDir() {
        return home.resolve("bin");
    }

    public Platform platform() {
        boolean unix = Files.isRegularFile(binDir().resolve(Platform.UNIX.domainEnv));
        boolean windows = Files.isRegularFile(binDir().resolve(Platform.WINDOWS.domainEnv));
        if (unix && !windows) {
            return Platform.UNIX;
        }
        if (windows && !unix) {
            return Platform.WINDOWS;
        }
        return Platform.current();
    }

    public Path setUserOverrides() {
        return binDir().resolve(platform().userOverrides);
    }

    public Path setDomainEnv() {
        return binDir().resolve(platform().domainEnv);
    }

    public Path installDir() {
        return home.resolve(INSTALL_DIR);
    }

    public Path installedAgentJar() {
        return installDir().resolve(AGENT_JAR);
    }

    public Path nodeManagerProperties() {
        return home.resolve("nodemanager").resolve("nodemanager.properties");
    }

    /** {@code <domain-version>} from config.xml, or "unknown". */
    public String webLogicVersion() {
        try {
            String xml = new String(Files.readAllBytes(configXml()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<domain-version>\\s*([^<\\s]+)\\s*</domain-version>").matcher(xml);
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * The domain's JDK as its setDomainEnv states it: the last literal assignment of JAVA_HOME,
     * else of SUN_JAVA_HOME, else of BEA_JAVA_HOME; null when there is none. A literal assignment
     * has no variable reference ({@code $} or {@code %}) in its value.
     */
    public Path domainJavaHome() {
        try {
            if (!Files.isRegularFile(setDomainEnv())) {
                return null;
            }
            String text = new String(Files.readAllBytes(setDomainEnv()), StandardCharsets.UTF_8);
            Pattern[] shapes = platform() == Platform.WINDOWS
                    ? new Pattern[]{assignment("(?i)set\\s+", "JAVA_HOME"), assignment("(?i)set\\s+", "SUN_JAVA_HOME"), assignment("(?i)set\\s+", "BEA_JAVA_HOME")}
                    : new Pattern[]{assignment("", "JAVA_HOME"), assignment("", "SUN_JAVA_HOME"), assignment("", "BEA_JAVA_HOME")};
            for (Pattern shape : shapes) {
                Matcher m = shape.matcher(text);
                Path last = null;
                while (m.find()) {
                    String value = m.group(1).trim();
                    if (!value.isEmpty() && !value.contains("$") && !value.contains("%") && !value.startsWith("@")) {
                        last = Paths.get(value);
                    }
                }
                if (last != null) {
                    return last;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Pattern assignment(String keyword, String variable) {
        return Pattern.compile("(?m)^\\s*" + keyword + variable + "=\"?([^\"\\r\\n]*)\"?\\s*$");
    }

    /** Major version of the domain's JDK (8, 11, 17, 21...), or 0 when it cannot be read. */
    public int domainJavaMajor() {
        Path javaHome = domainJavaHome();
        if (javaHome == null) {
            return 0;
        }
        Path release = javaHome.resolve("release");
        if (Files.isRegularFile(release)) {
            try (InputStream in = Files.newInputStream(release)) {
                Properties p = new Properties();
                p.load(in);
                return majorOf(p.getProperty("JAVA_VERSION", "").replace("\"", ""));
            } catch (IOException ignored) {
                // rt.jar below
            }
        }
        boolean rtJar = Files.isRegularFile(javaHome.resolve("jre").resolve("lib").resolve("rt.jar"))
                || Files.isRegularFile(javaHome.resolve("lib").resolve("rt.jar"));
        return rtJar ? 8 : 0;
    }

    public static int majorOf(String version) {
        if (version.isEmpty()) {
            return 0;
        }
        String v = version.startsWith("1.") ? version.substring(2) : version;
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(v.substring(0, end));
    }

    /** {@code StartScriptEnabled=false} in nodemanager.properties means the overrides file is not consulted. */
    public boolean nodeManagerSkipsStartScripts() {
        Path props = nodeManagerProperties();
        if (!Files.isRegularFile(props)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            return "false".equalsIgnoreCase(p.getProperty("StartScriptEnabled", "true").trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** True when {@code other} names this domain home, after normalisation; case-insensitively on Windows. */
    public boolean isHome(Path other) {
        return sameLocation(home, canonical(other), platform());
    }

    public static boolean sameLocation(Path a, Path b, Platform platform) {
        String left = a.normalize().toString();
        String right = b.normalize().toString();
        return platform == Platform.WINDOWS ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    /** The real path when it exists, else the absolute normalised one. */
    public static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    @Override
    public String toString() {
        return home.toString();
    }
}
