package io.capture.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

/**
 * Serves the page where captures are started and stopped, the active one is watched live and
 * previous ones are viewed, on {@code 127.0.0.1:<capture.port>}; opens it in the browser when
 * it can, and blocks until Ctrl-C. Refuses when the domain is not enabled on disk.
 */
@Mojo(name = "start", requiresProject = false, aggregator = true)
public final class StartMojo extends AbstractCaptureMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        prepare();
        DomainState state = monitor.evaluate();
        if (!state.enabledOnDisk()) {
            throw new MojoFailureException(state.message);
        }
        report(state);
        final WebServer web;
        try {
            web = new WebServer(monitor, new Recordings(outDir), port);
        } catch (IOException e) {
            throw new MojoExecutionException("cannot listen on 127.0.0.1:" + port + ": " + e.getMessage());
        }
        web.start();
        say("page: " + web.url() + "  (Ctrl-C to stop)");
        openBrowser(web.url());
        final CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                web.stop();
                monitor.close();
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void openBrowser(String url) {
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable ignored) {
            // fall through to the command-line openers
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] command = os.contains("mac") ? new String[]{"open", url}
                : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                : new String[]{"xdg-open", url};
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            say("open " + url + " in a browser");
        }
    }
}
