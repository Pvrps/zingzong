package ca.gc.cra.xz02.devops.wltrace.plugin.mojo;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.OverridesBlock;
import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Removes the agent from a WebLogic domain: stops an active recording, removes the block from the
 * overrides file (and the file, when enable created it), deletes {@code <domain>/weblogic-trace/}.
 * Recordings are never touched. A running server keeps the agent loaded, idle, until it restarts.
 */
@Mojo(name = "disable", requiresProject = false, aggregator = true)
public final class DisableMojo extends AbstractTraceMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare();
        DomainState before = monitor.evaluate();
        if (before.activeRecordingId != null) {
            List<String> stopped = monitor.stopRecording();
            say("stopped the active recording " + stopped + " first");
        }
        try {
            Path overrides = domain.overridesFile();
            boolean removed = new OverridesBlock(domain.platform()).remove(overrides);
            boolean fileGone = !Files.exists(overrides);
            say(removed
                    ? (fileGone ? "removed " + overrides + " (enable had created it)" : "removed the weblogic-trace block from " + overrides)
                    : "no weblogic-trace block in " + overrides);
            Path install = domain.installDirectory();
            if (Files.isDirectory(install)) {
                deleteRecursively(install);
                say("deleted " + install);
            } else {
                say("nothing to delete at " + install);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("disable failed: " + e.getMessage(), e);
        }
        DomainState after = monitor.evaluate();
        report(after);
        if (after.anyAgentLoaded()) {
            say("The agent stays loaded, idle, in the server(s) above until they restart; after that the domain is as it was.");
        } else {
            say("The domain is as it was before wltrace:enable.");
        }
        say("Recordings were left in " + outputDirectory);
        monitor.close();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
