package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that differs between a domain with Unix start scripts and one with Windows start
 * scripts: the names of the environment and overrides files, the comment marker and line ending
 * of the overrides file, how a line appends to {@code JAVA_OPTIONS}, how the JDK is assigned in
 * the environment script, and whether two paths name the same location. Nothing else in the
 * plugin asks which platform it is on.
 */
public abstract class DomainPlatform {

    /** The variables, in order of preference, whose last literal assignment names the domain's JDK. */
    static final List<String> JAVA_HOME_VARIABLES = java.util.Arrays.asList("JAVA_HOME", "SUN_JAVA_HOME", "BEA_JAVA_HOME");

    /** The platform of the JVM the plugin runs in. */
    public static DomainPlatform current() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? new WindowsPlatform() : new UnixPlatform();
    }

    /** {@code unix} or {@code windows}, for messages. */
    public abstract String name();

    /** {@code setDomainEnv.sh} or {@code setDomainEnv.cmd}: the script that names the domain's JDK. */
    public abstract String domainEnvFileName();

    /** {@code setUserOverrides.sh} or {@code setUserOverrides.cmd}: the file the agent is enabled in. */
    public abstract String overridesFileName();

    /** The comment prefix of the overrides file, {@code # } or {@code rem }. */
    public abstract String commentPrefix();

    /** The first line of an overrides file the plugin creates; empty when the file needs none. */
    public abstract String createdFileHeader();

    public abstract String lineEnding();

    /** One line that appends an option to {@code JAVA_OPTIONS}. */
    public abstract String appendToJavaOptions(String option);

    /** Lines that close the block after the options, such as the export a shell needs. */
    public abstract List<String> blockTrailer();

    /** Whether the overrides file must be executable for the server start script to run it. */
    public abstract boolean overridesFileExecutable();

    /** The pattern of a whole line assigning the variable, with the value as group 1. */
    protected abstract Pattern assignment(String variable);

    /** Whether two normalised paths name the same location: case matters on Unix, not on Windows. */
    public abstract boolean sameLocation(Path first, Path second);

    /**
     * The domain's JDK as its environment script states it: the last literal assignment of
     * JAVA_HOME, else SUN_JAVA_HOME, else BEA_JAVA_HOME. A literal assignment has no variable
     * reference ({@code $} or {@code %}) in its value.
     */
    public Optional<Path> javaHomeIn(String domainEnvText) {
        for (String variable : JAVA_HOME_VARIABLES) {
            Matcher matcher = assignment(variable).matcher(domainEnvText);
            Path last = null;
            while (matcher.find()) {
                String value = matcher.group(1).trim();
                if (!value.isEmpty() && !value.contains("$") && !value.contains("%") && !value.startsWith("@")) {
                    last = java.nio.file.Paths.get(value);
                }
            }
            if (last != null) {
                return Optional.of(last);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return name();
    }
}
