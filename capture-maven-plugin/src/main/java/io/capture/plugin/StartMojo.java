package io.capture.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

/**
 * Serves the page where captures are started and stopped, the active one is watched live and
 * previous ones are viewed, on {@code 127.0.0.1:<capture.port>}; opens it in the browser when
 * the desktop allows and blocks until Ctrl-C. Refuses when the domain is not enabled on disk.
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
        if (!openBrowser(web.url())) {
            say("open " + web.url() + " in a browser");
        }
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
