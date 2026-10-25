package ca.gc.cra.xz02.devops.wltrace.plugin.mojo;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain;
import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainState;
import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainStateMonitor;
import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.LocalJvms;
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
 * The three parameters every goal takes, all settable with {@code -D}, and what the goals share:
 * the verified domain, the agent jar shipped with this plugin, the output directory and the
 * state monitor.
 */
public abstract class AbstractTraceMojo extends AbstractMojo {

    static final String AGENT_GROUP_ID = "ca.gc.cra.xz02.devops";
    static final String AGENT_ARTIFACT_ID = "weblogic-trace-agent";

    /** The WebLogic domain home: the directory with {@code config/config.xml}. */
    @Parameter(property = "wltrace.domain", required = true)
    private File domainHome;

    /** Port of the page served by {@code wltrace:start}, on 127.0.0.1. */
    @Parameter(property = "wltrace.port", defaultValue = "8077")
    protected int port;

    /** Where recordings and agent.log go. Default: {@code ~/.weblogic-trace/<domain directory name>}. */
    @Parameter(property = "wltrace.out")
    private File out;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;

    protected Domain domain;
    protected Path outputDirectory;
    protected DomainStateMonitor monitor;

    /** Verifies the domain, resolves the agent jar and the output directory, and prepares the monitor. */
    protected void prepare() throws MojoExecutionException {
        domain = new Domain(domainHome.toPath());
        try {
            domain.verify();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        Path requested = out != null ? out.toPath()
                : Paths.get(System.getProperty("user.home"), ".weblogic-trace").resolve(domain.home.getFileName().toString());
        outputDirectory = Domain.canonical(requested);
        monitor = new DomainStateMonitor(domain, agentJar(), outputDirectory, new LocalJvms());
    }

    /** The agent jar among this plugin's resolved dependencies; never a path the user supplies. */
    private Path agentJar() throws MojoExecutionException {
        if (plugin != null && plugin.getArtifacts() != null) {
            for (Artifact artifact : plugin.getArtifacts()) {
                if (AGENT_GROUP_ID.equals(artifact.getGroupId()) && AGENT_ARTIFACT_ID.equals(artifact.getArtifactId()) && artifact.getFile() != null) {
                    return artifact.getFile().toPath();
                }
            }
        }
        throw new MojoExecutionException(AGENT_ARTIFACT_ID + ".jar not found among the plugin's dependencies");
    }

    /** What the person reads: on stdout, so that {@code mvn -q} shows it too. */
    protected void say(String line) {
        System.out.println(line);
    }

    protected void report(DomainState state) {
        say("domain " + domain + " (WebLogic " + domain.webLogicVersion() + ", " + domain.platform() + " start scripts)");
        say("state: " + state.message);
    }
}
