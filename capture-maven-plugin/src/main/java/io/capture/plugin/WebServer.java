package io.capture.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.capture.trace.RecordingIndex;
import io.capture.trace.TraceJson;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The page and its API on {@code 127.0.0.1:<port>}, on the JDK's own HTTP server.
 * <pre>
 *   GET  /, /index.html, /style.css, /app.js, /diagram.js   the page
 *   GET  /api/state                          DomainState, re-evaluated on every call
 *   POST /api/capture/start   {"name": ...}  start a recording on every server JVM
 *   POST /api/capture/stop
 *   GET  /api/recordings                     all recordings under the output directory, newest first
 *   GET  /api/recordings/{id}                the viewer index (traces, lineage)
 *   DELETE /api/recordings/{id}              remove a finished recording and its files (409 while active)
 *   GET  /api/recordings/{id}/traces/{file}  one trace file
 * </pre>
 */
public final class WebServer {

    private static final ObjectMapper JSON = TraceJson.mapper();
    /** The page's four files, served from the plugin's resources. */
    private static final Map<String, String> STATIC_FILES = new HashMap<String, String>();

    static {
        STATIC_FILES.put("index.html", "text/html; charset=utf-8");
        STATIC_FILES.put("style.css", "text/css; charset=utf-8");
        STATIC_FILES.put("app.js", "text/javascript; charset=utf-8");
        STATIC_FILES.put("diagram.js", "text/javascript; charset=utf-8");
    }

    private final StateMonitor monitor;
    private final Recordings recordings;
    private final HttpServer server;

    public WebServer(StateMonitor monitor, Recordings recordings, int port) throws IOException {
        this.monitor = monitor;
        this.recordings = recordings;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.createContext("/", new Handler() {
            @Override
            void handle(HttpExchange x, String path) throws Exception {
                String file = path.equals("/") ? "index.html" : path.substring(1);
                String type = STATIC_FILES.get(file);
                if (type != null) {
                    send(x, 200, type, resource("io/capture/plugin/" + file));
                } else {
                    send(x, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.createContext("/api/state", new Handler() {
            @Override
            void handle(HttpExchange x, String path) throws Exception {
                json(x, 200, state(monitor.evaluate()));
            }
        });
        server.createContext("/api/capture/start", new Handler() {
            @Override
            void handle(HttpExchange x, String path) throws Exception {
                if (!x.getRequestMethod().equals("POST")) {
                    json(x, 405, error("POST"));
                    return;
                }
                String name = null;
                byte[] body = readAll(x.getRequestBody());
                if (body.length > 0) {
                    JsonNode node = JSON.readTree(body);
                    name = node.path("name").asText(null);
                }
                try {
                    String id = monitor.startCapture(name);
                    ObjectNode result = JSON.createObjectNode();
                    result.put("id", id);
                    json(x, 200, result);
                } catch (IllegalStateException e) {
                    json(x, 409, error(e.getMessage()));
                }
            }
        });
        server.createContext("/api/capture/stop", new Handler() {
            @Override
            void handle(HttpExchange x, String path) throws Exception {
                if (!x.getRequestMethod().equals("POST")) {
                    json(x, 405, error("POST"));
                    return;
                }
                List<String> stopped = monitor.stopCapture();
                ObjectNode result = JSON.createObjectNode();
                ArrayNode ids = result.putArray("stopped");
                for (String id : stopped) {
                    ids.add(id);
                }
                json(x, 200, result);
            }
        });
        server.createContext("/api/recordings", new Handler() {
            @Override
            void handle(HttpExchange x, String path) throws Exception {
                String rest = path.substring("/api/recordings".length());
                if (rest.isEmpty() || rest.equals("/")) {
                    json(x, 200, list());
                    return;
                }
                String[] parts = rest.substring(1).split("/");
                String id = parts[0];
                if (!recordings.exists(id)) {
                    json(x, 404, error("no recording " + id));
                } else if (parts.length == 1 && x.getRequestMethod().equals("DELETE")) {
                    try {
                        int files = recordings.delete(id, monitor.evaluate().activeRecordingId);
                        ObjectNode result = JSON.createObjectNode();
                        result.put("deleted", id);
                        result.put("files", files);
                        json(x, 200, result);
                    } catch (IllegalStateException e) {
                        json(x, 409, error(e.getMessage()));
                    }
                } else if (parts.length == 1) {
                    RecordingIndex index = recordings.index(id);
                    json(x, 200, JSON.valueToTree(index));
                } else if (parts.length == 3 && parts[1].equals("traces")) {
                    Path f = recordings.traceFile(id, parts[2]);
                    if (f == null) {
                        json(x, 404, error("no trace " + parts[2]));
                    } else {
                        send(x, 200, "application/json", Files.readAllBytes(f));
                    }
                } else {
                    json(x, 404, error("not found"));
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    // ---- JSON views ------------------------------------------------------------------------------

    ObjectNode state(DomainState s) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", s.kind.name());
        n.put("message", s.message);
        n.put("ready", s.ready());
        n.put("enabledOnDisk", s.enabledOnDisk());
        n.put("blockPresent", s.blockPresent);
        n.put("jarPresent", s.jarPresent);
        n.put("jarCurrent", s.jarCurrent);
        n.put("domain", monitor.domain().home.toString());
        n.put("webLogicVersion", monitor.domain().webLogicVersion());
        n.put("outDir", recordings.outDir().toString());
        n.put("pluginAgentBuild", monitor.pluginAgentBuild());
        n.put("activeRecordingId", s.activeRecordingId);
        n.put("activeRecordingName", s.activeRecordingName);
        ArrayNode servers = n.putArray("servers");
        for (DomainState.Server server : s.servers) {
            ObjectNode sn = servers.addObject();
            sn.put("pid", server.pid);
            sn.put("serverName", server.serverName);
            sn.put("agentLoaded", server.agentLoaded);
            sn.put("agentBuild", server.agentBuild);
            sn.put("agentCurrent", server.agentBuild != null && server.agentBuild.equals(monitor.pluginAgentBuild()));
            sn.put("recording", server.recording);
            sn.put("recordingId", server.recordingId);
            sn.put("recordingName", server.recordingName);
            sn.put("traceCount", server.traceCount);
            sn.put("droppedTraces", server.droppedTraces);
        }
        return n;
    }

    private ArrayNode list() {
        DomainState s = monitor.evaluate();
        ArrayNode arr = JSON.createArrayNode();
        for (Recordings.Summary r : recordings.list(s.activeRecordingId)) {
            ObjectNode n = arr.addObject();
            n.put("id", r.id);
            n.put("name", r.name);
            n.put("startedAt", r.startedAt);
            n.put("endedAt", r.endedAt);
            n.put("traceCount", r.traceCount);
            n.put("active", r.active);
            ArrayNode servers = n.putArray("servers");
            for (String server : r.servers) {
                servers.add(server);
            }
        }
        return arr;
    }

    private static ObjectNode error(String message) {
        ObjectNode n = JSON.createObjectNode();
        n.put("error", message);
        return n;
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private abstract static class Handler implements HttpHandler {
        abstract void handle(HttpExchange x, String path) throws Exception;

        @Override
        public final void handle(HttpExchange x) throws IOException {
            try {
                handle(x, x.getRequestURI().getPath());
            } catch (Exception e) {
                json(x, 500, error(String.valueOf(e)));
            } finally {
                x.close();
            }
        }
    }

    static void json(HttpExchange x, int status, JsonNode body) throws IOException {
        send(x, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    static void send(HttpExchange x, int status, String contentType, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", contentType);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, body.length);
        OutputStream out = x.getResponseBody();
        out.write(body);
        out.close();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    static byte[] resource(String name) throws IOException {
        InputStream in = WebServer.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IOException("missing resource " + name);
        }
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }
}
