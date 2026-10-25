package ca.gc.cra.xz02.devops.wltrace.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Installs the capture agent in a WebLogic domain: copies the agent jar to
 * {@code <domain>/weblogic-trace/} and writes the {@code -javaagent} option (plus the JDK 9+
 * {@code --add-opens} flags) into the domain's overrides file inside a delimited block.
 * Idempotent; a running server picks it up at its next start.
 */
@Mojo(name = "enable", requiresProject = false, aggregator = true)
public final class EnableMojo extends AbstractCaptureMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare();
        try {
            Path jar = domain.installedAgentJar();
            Files.createDirectories(jar.getParent());
            // Replace by rename: a running server has the old jar mapped and must keep it intact.
            Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
            Files.copy(monitor.pluginAgentJar(), tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(outDir);
            int javaMajor = domain.domainJavaMajor();
            String javaSource = "bin/" + domain.platform().domainEnv;
            if (javaMajor == 0) {
                javaMajor = monitor.runningServerJavaMajor();
                javaSource = "the running server";
            }
            Path overrides = domain.setUserOverrides();
            boolean created = OverridesBlock.install(overrides, OverridesBlock.Syntax.of(domain.platform()), jar, outDir, javaMajor);
            say("installed " + jar + " (build " + monitor.pluginAgentBuild() + ")");
            say((created ? "created " : "updated ") + overrides + " (weblogic-trace block"
                    + (javaMajor >= 9 ? "; JDK " + javaMajor + " per " + javaSource + ": with --add-opens" : javaMajor > 0 ? "; JDK " + javaMajor + " per " + javaSource : "") + ")");
            if (javaMajor == 0) {
                say("WARNING: the domain's JDK could not be read from bin/" + domain.platform().domainEnv + " and no server is running; no --add-opens flags were added.");
                say("         If the domain runs on JDK 9 or later, run wltrace:enable again while a server is up, or add them to the block yourself.");
            }
            say("recordings will go to " + outDir);
            if (domain.nodeManagerSkipsStartScripts()) {
                say("WARNING: nodemanager/nodemanager.properties has StartScriptEnabled=false: servers started by Node Manager do not read " + overrides.getFileName() + ".");
                say("         For each such server add to servers/<name>/data/nodemanager/startup.properties:");
                say("           Arguments=" + propertiesValue(OverridesBlock.javaOptions(jar, outDir, javaMajor)));
            }
        } catch (IOException e) {
            throw new MojoExecutionException("enable failed: " + e.getMessage(), e);
        }
        DomainState state = monitor.evaluate();
        report(state);
        if (state.kind == DomainState.Kind.ENABLED_SERVER_NO_AGENT) {
            say("Restart the server(s) listed above to load the agent; nothing is captured until then.");
        } else if (state.kind == DomainState.Kind.ENABLED_SERVER_DOWN) {
            say("Start the domain, then run wltrace:start.");
        } else if (state.ready()) {
            say("Run wltrace:start to open the page.");
        }
        monitor.close();
    }

    /** A value for a .properties file: backslashes, as in Windows paths, must be doubled. */
    static String propertiesValue(String value) {
        return value.replace("\\", "\\\\");
    }
}
