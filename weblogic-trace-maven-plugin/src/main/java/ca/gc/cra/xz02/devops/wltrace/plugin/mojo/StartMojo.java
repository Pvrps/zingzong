package ca.gc.cra.xz02.devops.wltrace.plugin.mojo;

import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainState;
import ca.gc.cra.xz02.devops.wltrace.plugin.jvm.MonitoredDomainControl;
import ca.gc.cra.xz02.devops.wltrace.viewer.web.ViewerServer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

/**
 * Serves the page where recordings are started and stopped, the active one is watched live and
 * earlier ones are viewed, on {@code 127.0.0.1:<wltrace.port>}; opens it in the browser when the
 * desktop allows and blocks until Ctrl-C. Refuses when the domain is not enabled on disk.
 */
@Mojo(name = "start", requiresProject = false, aggregator = true)
public final class StartMojo extends AbstractTraceMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        prepare();
        DomainState state = monitor.evaluate();
        if (!state.enabledOnDisk()) {
            throw new MojoFailureException(state.message);
        }
        report(state);
        ViewerServer viewer;
        try {
            viewer = ViewerServer.start(outputDirectory, port, new MonitoredDomainControl(monitor));
        } catch (IOException e) {
            throw new MojoExecutionException("cannot listen on 127.0.0.1:" + port + ": " + e.getMessage());
        }
        say("page: " + viewer.url() + "  (Ctrl-C to stop)");
        if (!openBrowser(viewer.url())) {
            say("open " + viewer.url() + " in a browser");
        }
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            viewer.stop();
            monitor.close();
            done.countDown();
        }));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean openBrowser(String url) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Throwable ignored) {
            // the URL is printed instead
        }
        return false;
    }
}
