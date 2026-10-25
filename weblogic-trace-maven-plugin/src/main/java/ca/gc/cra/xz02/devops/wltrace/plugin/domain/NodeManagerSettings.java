package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * What {@code nodemanager/nodemanager.properties} says about how servers start. With
 * {@code StartScriptEnabled=false} Node Manager starts servers without the start scripts, so
 * the overrides file is never read and the options must go into each server's
 * {@code startup.properties} instead.
 */
public final class NodeManagerSettings {

    private final Domain domain;

    public NodeManagerSettings(Domain domain) {
        this.domain = domain;
    }

    public boolean skipsStartScripts() {
        Path properties = domain.nodeManagerProperties();
        if (!Files.isRegularFile(properties)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(properties)) {
            Properties settings = new Properties();
            settings.load(in);
            return "false".equalsIgnoreCase(settings.getProperty("StartScriptEnabled", "true").trim());
        } catch (IOException failure) {
            return false;
        }
    }

    /** A value for a {@code .properties} file: backslashes, as in Windows paths, are doubled. */
    public static String propertiesValue(String value) {
        return value.replace("\\", "\\\\");
    }
}
