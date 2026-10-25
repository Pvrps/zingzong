package ca.gc.cra.xz02.devops.wltrace.plugin.mojo;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.DomainJdk;
import ca.gc.cra.xz02.devops.wltrace.plugin.domain.NodeManagerSettings;
import ca.gc.cra.xz02.devops.wltrace.plugin.domain.OverridesBlock;
import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Installs the agent in a WebLogic domain: copies the agent jar to {@code <domain>/weblogic-trace/}
 * and writes the {@code -javaagent} option, plus the {@code --add-opens} flags on a JDK 9 or
 * later domain, into the domain's overrides file inside a delimited block. Idempotent; a running
 * server picks it up at its next start.
 */
@Mojo(name = "enable", requiresProject = false, aggregator = true)
public final class EnableMojo extends AbstractTraceMojo {

    @Override
    public void execute() throws MojoExecutionException {
        prepare();
        try {
            Path jar = domain.installedAgentJar();
            Files.createDirectories(jar.getParent());
            // Replace by rename: a running server has the old jar mapped and must keep it intact.
            Path temporary = jar.resolveSibling(jar.getFileName() + ".tmp");
            Files.copy(monitor.pluginAgentJar(), temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(outputDirectory);

            int javaMajor = new DomainJdk(domain).majorVersion();
            String javaSource = "bin/" + domain.platform().domainEnvFileName();
            if (javaMajor == 0) {
                javaMajor = monitor.runningServerJavaMajor();
                javaSource = "the running server";
            }
            Path overrides = domain.overridesFile();
            boolean created = new OverridesBlock(domain.platform()).install(overrides, jar, outputDirectory, javaMajor);
            say("installed " + jar + " (build " + monitor.pluginAgentBuild() + ")");
            say((created ? "created " : "updated ") + overrides + " (weblogic-trace block" + jdkNote(javaMajor, javaSource) + ")");
            if (javaMajor == 0) {
                say("WARNING: the domain's JDK could not be read from bin/" + domain.platform().domainEnvFileName()
                        + " and no server is running; no --add-opens flags were added.");
                say("         If the domain runs on JDK 9 or later, run wltrace:enable again while a server is up, or add them to the block yourself.");
            }
            say("recordings will go to " + outputDirectory);
            if (new NodeManagerSettings(domain).skipsStartScripts()) {
                say("WARNING: nodemanager/nodemanager.properties has StartScriptEnabled=false: servers started by Node Manager do not read "
                        + overrides.getFileName() + ".");
                say("         For each such server add to servers/<name>/data/nodemanager/startup.properties:");
                say("           Arguments=" + NodeManagerSettings.propertiesValue(OverridesBlock.javaOptions(jar, outputDirectory, javaMajor)));
            }
        } catch (IOException failure) {
            throw new MojoExecutionException("enable failed: " + failure.getMessage(), failure);
        }
        DomainState state = monitor.evaluate();
        report(state);
        if (state.kind == DomainState.Kind.ENABLED_SERVER_NO_AGENT) {
            say("Restart the server(s) listed above to load the agent; nothing is recorded until then.");
        } else if (state.kind == DomainState.Kind.ENABLED_SERVER_DOWN) {
            say("Start the domain, then run wltrace:start.");
        } else if (state.ready()) {
            say("Run wltrace:start to open the page.");
        }
        monitor.close();
    }

    private static String jdkNote(int javaMajor, String javaSource) {
        if (javaMajor >= DomainJdk.FIRST_MODULAR_MAJOR) {
            return "; JDK " + javaMajor + " per " + javaSource + ": with --add-opens";
        }
        return javaMajor > 0 ? "; JDK " + javaMajor + " per " + javaSource : "";
    }
}
