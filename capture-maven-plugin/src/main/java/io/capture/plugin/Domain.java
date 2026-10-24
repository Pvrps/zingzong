package io.capture.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A WebLogic domain home and the files the plugin reads or writes in it. */
public final class Domain {

    public static final String INSTALL_DIR = "ejb-capture";
    public static final String AGENT_JAR = "capture-agent.jar";

    public final File home;

    public Domain(File home) {
        this.home = canonical(home);
    }

    /** Fails with a plain explanation when this is not a WebLogic domain. */
    public void verify() throws IOException {
        if (!home.isDirectory()) {
            throw new IOException(home + " is not a directory");
        }
        if (!configXml().isFile()) {
            throw new IOException(home + " is not a WebLogic domain: no config/config.xml");
        }
    }

    public File configXml() {
        return new File(new File(home, "config"), "config.xml");
    }

    public File binDir() {
        return new File(home, "bin");
    }

    public File setUserOverrides() {
        return new File(binDir(), "setUserOverrides.sh");
    }

    public File setDomainEnv() {
        return new File(binDir(), "setDomainEnv.sh");
    }

    public File installDir() {
        return new File(home, INSTALL_DIR);
    }

    public File installedAgentJar() {
        return new File(installDir(), AGENT_JAR);
    }

    public File nodeManagerProperties() {
        return new File(new File(home, "nodemanager"), "nodemanager.properties");
    }

    /** {@code <domain-version>} from config.xml, or "unknown". */
    public String webLogicVersion() {
        try {
            String xml = new String(Files.readAllBytes(configXml().toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<domain-version>\\s*([^<\\s]+)\\s*</domain-version>").matcher(xml);
            return m.find() ? m.group(1) : "unknown";
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * The domain's JDK as setDomainEnv.sh states it: the last literal assignment (no {@code $})
     * of JAVA_HOME, else of SUN_JAVA_HOME, else of BEA_JAVA_HOME; null when there is none.
     */
    public File domainJavaHome() {
        try {
            if (!setDomainEnv().isFile()) {
                return null;
            }
            String text = new String(Files.readAllBytes(setDomainEnv().toPath()), StandardCharsets.UTF_8);
            for (String variable : new String[]{"JAVA_HOME", "SUN_JAVA_HOME", "BEA_JAVA_HOME"}) {
                Matcher m = Pattern.compile("(?m)^\\s*" + variable + "=\"?([^\"\\n]*)\"?\\s*$").matcher(text);
                File last = null;
                while (m.find()) {
                    String value = m.group(1).trim();
                    if (!value.isEmpty() && !value.contains("$") && !value.startsWith("@")) {
                        last = new File(value);
                    }
                }
                if (last != null) {
                    return last;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Major version of the domain's JDK (8, 11, 17, 21...), or 0 when it cannot be read. */
    public int domainJavaMajor() {
        File javaHome = domainJavaHome();
        if (javaHome == null) {
            return 0;
        }
        File release = new File(javaHome, "release");
        if (release.isFile()) {
            try (java.io.InputStream in = Files.newInputStream(release.toPath())) {
                Properties p = new Properties();
                p.load(in);
                String version = p.getProperty("JAVA_VERSION", "").replace("\"", "");
                return majorOf(version);
            } catch (IOException ignored) {
                // fall through
            }
        }
        // JDK 8 has no "release" file in every distribution; a jre/lib/rt.jar is a sure sign.
        return new File(javaHome, "jre/lib/rt.jar").isFile() || new File(javaHome, "lib/rt.jar").isFile() ? 8 : 0;
    }

    static int majorOf(String version) {
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

    /** {@code StartScriptEnabled=false} in nodemanager.properties means setUserOverrides.sh is not consulted. */
    public boolean nodeManagerSkipsStartScripts() {
        File props = nodeManagerProperties();
        if (!props.isFile()) {
            return false;
        }
        try (java.io.InputStream in = Files.newInputStream(props.toPath())) {
            Properties p = new Properties();
            p.load(in);
            return "false".equalsIgnoreCase(p.getProperty("StartScriptEnabled", "true").trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }

    @Override
    public String toString() {
        return home.getPath();
    }
}
