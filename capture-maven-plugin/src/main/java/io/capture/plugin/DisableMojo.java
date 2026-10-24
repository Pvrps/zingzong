package io.capture.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Removes the capture agent from a WebLogic domain: stops an active recording, removes the
 * ejb-capture block from {@code bin/setUserOverrides.sh} (and the file, when enable created
 * it), deletes {@code <domain>/ejb-capture/}. Recordings are never touched. A running server
 * keeps the agent loaded, idle, until it restarts.
 */
@Mojo(name = "disable", requiresProject = false, aggregator = true)
public final class DisableMojo extends AbstractCaptureMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare();
        DomainState before = monitor.evaluate();
        if (before.activeRecordingId != null) {
            List<String> stopped = monitor.stopCapture();
            say("stopped the active recording " + stopped + " first");
        }
        try {
            boolean removed = OverridesBlock.remove(domain.setUserOverrides());
            boolean fileGone = !domain.setUserOverrides().exists();
            say(removed
                    ? (fileGone ? "removed " + domain.setUserOverrides() + " (enable had created it)" : "removed the ejb-capture block from " + domain.setUserOverrides())
                    : "no ejb-capture block in " + domain.setUserOverrides());
            File install = domain.installDir();
            if (install.isDirectory()) {
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
            say("The domain is as it was before capture:enable.");
        }
        say("Recordings were left in " + outDir);
        monitor.close();
    }

    private static void deleteRecursively(File f) throws IOException {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        if (!f.delete() && f.exists()) {
            throw new IOException("cannot delete " + f);
        }
    }
}
