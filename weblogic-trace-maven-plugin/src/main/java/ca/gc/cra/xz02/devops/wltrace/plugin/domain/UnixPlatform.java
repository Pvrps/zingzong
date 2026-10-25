package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** A domain with {@code bin/setDomainEnv.sh}: shell syntax, LF line endings, case-sensitive paths. */
public final class UnixPlatform extends DomainPlatform {

    @Override
    public String name() {
        return "unix";
    }

    @Override
    public String domainEnvFileName() {
        return "setDomainEnv.sh";
    }

    @Override
    public String overridesFileName() {
        return "setUserOverrides.sh";
    }

    @Override
    public String commentPrefix() {
        return "# ";
    }

    @Override
    public String createdFileHeader() {
        return "#!/bin/sh";
    }

    @Override
    public String lineEnding() {
        return "\n";
    }

    @Override
    public String appendToJavaOptions(String option) {
        return "JAVA_OPTIONS=\"${JAVA_OPTIONS} " + option + "\"";
    }

    @Override
    public List<String> blockTrailer() {
        return Collections.singletonList("export JAVA_OPTIONS");
    }

    @Override
    public boolean overridesFileExecutable() {
        return true;
    }

    @Override
    protected Pattern assignment(String variable) {
        return Pattern.compile("(?m)^\\s*" + variable + "=\"?([^\"\\r\\n]*)\"?\\s*$");
    }

    @Override
    public boolean sameLocation(Path first, Path second) {
        return first.normalize().toString().equals(second.normalize().toString());
    }
}
