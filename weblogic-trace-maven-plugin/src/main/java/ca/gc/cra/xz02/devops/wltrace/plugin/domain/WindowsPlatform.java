package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A domain with {@code bin\setDomainEnv.cmd}: cmd syntax, CRLF line endings, an option quoted
 * when it holds a space or a character cmd would interpret, case-insensitive paths.
 */
public final class WindowsPlatform extends DomainPlatform {

    private static final String CMD_METACHARACTERS = "&|<>^()";

    @Override
    public String name() {
        return "windows";
    }

    @Override
    public String domainEnvFileName() {
        return "setDomainEnv.cmd";
    }

    @Override
    public String overridesFileName() {
        return "setUserOverrides.cmd";
    }

    @Override
    public String commentPrefix() {
        return "rem ";
    }

    @Override
    public String createdFileHeader() {
        return "";
    }

    @Override
    public String lineEnding() {
        return "\r\n";
    }

    @Override
    public String appendToJavaOptions(String option) {
        return "set JAVA_OPTIONS=%JAVA_OPTIONS% " + quote(option);
    }

    @Override
    public List<String> blockTrailer() {
        return Collections.emptyList();
    }

    @Override
    public boolean overridesFileExecutable() {
        return false;
    }

    @Override
    protected Pattern assignment(String variable) {
        return Pattern.compile("(?m)^\\s*(?i)set\\s+" + variable + "=\"?([^\"\\r\\n]*)\"?\\s*$");
    }

    @Override
    public boolean sameLocation(Path first, Path second) {
        return first.normalize().toString().equalsIgnoreCase(second.normalize().toString());
    }

    /** Wraps a cmd {@code set} value fragment in double quotes when it has a space or a cmd metacharacter. */
    static String quote(String option) {
        for (char character : option.toCharArray()) {
            if (character == ' ' || CMD_METACHARACTERS.indexOf(character) >= 0) {
                return "\"" + option + "\"";
            }
        }
        return option;
    }
}
