package io.capture.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Installs the capture agent in a WebLogic domain: copies the agent jar to
 * {@code <domain>/ejb-capture/} and adds the {@code -javaagent} line (plus the JDK 9+
 * {@code --add-opens} flags) to {@code bin/setUserOverrides.sh} inside a delimited block.
 * Idempotent; a running server picks it up at its next start.
 */
@Mojo(name = "enable", requiresProject = false, aggregator = true)
public final class EnableMojo extends AbstractCaptureMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare();
        try {
            File jar = domain.installedAgentJar();
            File dir = jar.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            // Replace by rename: a running server has the old jar mapped and must keep it intact.
            File tmp = new File(dir, jar.getName() + ".tmp");
            Files.copy(monitor.pluginAgentJar().toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp.toPath(), jar.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                throw new IOException("cannot create " + outDir);
            }
            int javaMajor = domain.domainJavaMajor();
            String javaSource = "bin/setDomainEnv.sh";
            if (javaMajor == 0) {
                javaMajor = monitor.runningServerJavaMajor();
                javaSource = "the running server";
            }
            boolean created = OverridesBlock.install(domain.setUserOverrides(), jar, outDir, javaMajor);
            say("installed " + jar + " (build " + monitor.pluginAgentBuild() + ")");
            say((created ? "created " : "updated ") + domain.setUserOverrides() + " (ejb-capture block"
                    + (javaMajor >= 9 ? "; JDK " + javaMajor + " per " + javaSource + ": with --add-opens" : javaMajor > 0 ? "; JDK " + javaMajor + " per " + javaSource : "") + ")");
            if (javaMajor == 0) {
                say("WARNING: the domain's JDK could not be read from bin/setDomainEnv.sh and no server is running; no --add-opens flags were added.");
                say("         If the domain runs on JDK 9 or later, run capture:enable again while a server is up, or add them to the block yourself.");
            }
            say("recordings will go to " + outDir);
            if (domain.nodeManagerSkipsStartScripts()) {
                say("WARNING: nodemanager/nodemanager.properties has StartScriptEnabled=false: servers started by Node Manager do not read setUserOverrides.sh.");
                say("         For each such server add to servers/<name>/data/nodemanager/startup.properties:");
                say("           Arguments=" + OverridesBlock.javaOptions(jar, outDir, javaMajor));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("enable failed: " + e.getMessage(), e);
        }
        DomainState state = monitor.evaluate();
        report(state);
        if (state.kind == DomainState.Kind.ENABLED_SERVER_NO_AGENT) {
            say("Restart the server(s) listed above to load the agent; nothing is captured until then.");
        } else if (state.kind == DomainState.Kind.ENABLED_SERVER_DOWN) {
            say("Start the domain, then run capture:start.");
        } else if (state.ready()) {
            say("Run capture:start to open the page.");
        }
        monitor.close();
    }
}
