package ca.gc.cra.xz02.devops.wltrace.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The three parameters every goal takes, all settable with {@code -D}, and the plumbing they
 * share: the domain, the agent jar shipped with this plugin, the state monitor.
 */
public abstract class AbstractCaptureMojo extends AbstractMojo {

    /** The WebLogic domain home (the directory with config/config.xml). */
    @Parameter(property = "wltrace.domain", required = true)
    private File domainHome;

    /** Port of the page served by wltrace:start, on 127.0.0.1. */
    @Parameter(property = "wltrace.port", defaultValue = "8077")
    protected int port;

    /** Where recordings go. Default: {@code ~/.weblogic-trace/<domain directory name>}. */
    @Parameter(property = "wltrace.out")
    private File out;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;

    protected Domain domain;
    protected Path outDir;
    protected StateMonitor monitor;

    /** Verifies the domain, resolves the agent jar and the output directory. */
    protected void prepare() throws MojoExecutionException {
        domain = new Domain(domainHome.toPath());
        try {
            domain.verify();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        Path dir = out != null ? out.toPath()
                : Paths.get(System.getProperty("user.home"), ".weblogic-trace").resolve(domain.home.getFileName().toString());
        outDir = Domain.canonical(dir);
        monitor = new StateMonitor(domain, agentJar(), outDir);
    }

    /** The weblogic-trace-agent jar among this plugin's resolved dependencies. */
    private Path agentJar() throws MojoExecutionException {
        if (plugin != null && plugin.getArtifacts() != null) {
            for (Artifact a : plugin.getArtifacts()) {
                if ("ca.gc.cra.xz02.devops".equals(a.getGroupId()) && "weblogic-trace-agent".equals(a.getArtifactId()) && a.getFile() != null) {
                    return a.getFile().toPath();
                }
            }
        }
        throw new MojoExecutionException("weblogic-trace-agent.jar not found among the plugin's dependencies");
    }

    /** What the person reads: on stdout, so that {@code mvn -q} shows it too. */
    protected void say(String line) {
        System.out.println(line);
    }

    protected void report(DomainState state) {
        say("domain " + domain + " (WebLogic " + domain.webLogicVersion() + ", " + domain.platform().name().toLowerCase() + " start scripts)");
        say("state: " + state.message);
    }
}
