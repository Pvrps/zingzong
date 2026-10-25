package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * The JDK a domain's servers start with, read from the domain's environment script through the
 * platform's assignment syntax, and its major version from {@code <jdk>/release} or, for a JDK 8
 * without that file, from the presence of {@code rt.jar}.
 */
public final class DomainJdk {

    /** The major version below which no {@code --add-opens} flags exist. */
    public static final int FIRST_MODULAR_MAJOR = 9;

    private final Domain domain;

    public DomainJdk(Domain domain) {
        this.domain = domain;
    }

    /** The JDK home the environment script names, or empty when it names none literally. */
    public Optional<Path> javaHome() {
        try {
            Path domainEnv = domain.domainEnvFile();
            if (!Files.isRegularFile(domainEnv)) {
                return Optional.empty();
            }
            String text = new String(Files.readAllBytes(domainEnv), StandardCharsets.UTF_8);
            return domain.platform().javaHomeIn(text);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Major version of the domain's JDK (8, 11, 17, 21, ...), or 0 when it cannot be read. */
    public int majorVersion() {
        Optional<Path> javaHome = javaHome();
        if (!javaHome.isPresent()) {
            return 0;
        }
        Path release = javaHome.get().resolve("release");
        if (Files.isRegularFile(release)) {
            try (InputStream in = Files.newInputStream(release)) {
                Properties properties = new Properties();
                properties.load(in);
                return majorOf(properties.getProperty("JAVA_VERSION", "").replace("\"", ""));
            } catch (IOException ignored) {
                // rt.jar below
            }
        }
        boolean rtJar = Files.isRegularFile(javaHome.get().resolve("jre").resolve("lib").resolve("rt.jar"))
                || Files.isRegularFile(javaHome.get().resolve("lib").resolve("rt.jar"));
        return rtJar ? 8 : 0;
    }

    /** The major version in a version string: {@code 1.8.0_392} is 8, {@code 21.0.1} is 21; 0 for an unreadable string. */
    public static int majorOf(String version) {
        if (version.isEmpty()) {
            return 0;
        }
        String withoutLegacyPrefix = version.startsWith("1.") ? version.substring(2) : version;
        int end = 0;
        while (end < withoutLegacyPrefix.length() && Character.isDigit(withoutLegacyPrefix.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(withoutLegacyPrefix.substring(0, end));
    }
}
