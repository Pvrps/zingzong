package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Serves the page's four files from this package's resources: {@code /} is {@code index.html}. */
final class PageHandler extends JsonHandler {

    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put("index.html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("style.css", "text/css; charset=utf-8");
        CONTENT_TYPES.put("app.js", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put("diagram.js", "text/javascript; charset=utf-8");
    }

    @Override
    void handle(HttpExchange exchange, String path) throws Exception {
        String file = path.equals("/") ? "index.html" : path.substring(1);
        String contentType = CONTENT_TYPES.get(file);
        if (contentType == null) {
            send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(exchange, 200, contentType, resource(file));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = PageHandler.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            return readAll(in);
        }
    }
}
