package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import ca.gc.cra.xz02.devops.wltrace.viewer.recording.RecordingCatalog;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * The page and its API on {@code 127.0.0.1:<port>}, on the JDK's own HTTP server: the page files
 * ({@link PageHandler}), the state ({@link StateHandler}), start and stop ({@link ControlHandler})
 * and the recordings ({@link RecordingsHandler}). {@link #start} binds and serves; {@link #stop}
 * closes the listener.
 */
public final class ViewerServer {

    private final HttpServer server;

    private ViewerServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Serves the recordings under {@code recordings}, controlled through {@code control}, on
     * {@code 127.0.0.1:port}; the server is listening when this returns.
     *
     * @throws IOException when the port cannot be bound
     */
    public static ViewerServer start(Path recordings, int port, DomainControl control) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        RecordingCatalog catalog = new RecordingCatalog(recordings);
        server.createContext("/", new PageHandler());
        server.createContext("/api/state", new StateHandler(control, recordings));
        server.createContext("/api/control", new ControlHandler(control));
        server.createContext(RecordingsHandler.ROOT, new RecordingsHandler(control, catalog));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return new ViewerServer(server);
    }

    public void stop() {
        server.stop(0);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
}
