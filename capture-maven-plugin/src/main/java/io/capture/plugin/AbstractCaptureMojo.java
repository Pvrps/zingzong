package io.capture.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;

/**
 * The three parameters every goal takes, all settable with {@code -D}, and the plumbing they
 * share: the domain, the agent jar shipped with this plugin, the state monitor.
 */
public abstract class AbstractCaptureMojo extends AbstractMojo {

    /** The WebLogic domain home (the directory with config/config.xml). */
    @Parameter(property = "capture.domain", required = true)
    private File domainHome;

    /** Port of the page served by capture:start, on 127.0.0.1. */
    @Parameter(property = "capture.port", defaultValue = "8077")
    protected int port;

    /** Where recordings go. Default: {@code ~/.ejb-capture/<domain directory name>}. */
    @Parameter(property = "capture.out")
    private File out;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;

    protected Domain domain;
    protected File outDir;
    protected StateMonitor monitor;

    /** Verifies the domain, resolves the agent jar and the output directory. */
    protected void prepare() throws MojoExecutionException {
        domain = new Domain(domainHome);
        try {
            domain.verify();
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        File dir = out != null ? out : new File(new File(System.getProperty("user.home"), ".ejb-capture"), domain.home.getName());
        outDir = Domain.canonical(dir);
        monitor = new StateMonitor(domain, agentJar(), outDir);
    }

    /** The capture-agent jar among this plugin's resolved dependencies. */
    private File agentJar() throws MojoExecutionException {
        if (plugin != null && plugin.getArtifacts() != null) {
            for (Artifact a : plugin.getArtifacts()) {
                if ("io.capture".equals(a.getGroupId()) && "capture-agent".equals(a.getArtifactId()) && a.getFile() != null) {
                    return a.getFile();
                }
            }
        }
        throw new MojoExecutionException("capture-agent.jar not found among the plugin's dependencies");
    }

    /** What the person reads: on stdout, so that {@code mvn -q} shows it too. */
    protected void say(String line) {
        System.out.println(line);
    }

    protected void report(DomainState state) {
        say("domain " + domain + " (WebLogic " + domain.webLogicVersion() + ")");
        say("state: " + state.message);
    }
}
