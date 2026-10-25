package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * What every endpoint handler shares: the request path, JSON in and out, and the rule that a
 * failure becomes a 500 with the exception as its message rather than a dropped connection.
 */
abstract class JsonHandler implements HttpHandler {

    static final ObjectMapper JSON = TraceJson.mapper();

    abstract void handle(HttpExchange exchange, String path) throws Exception;

    @Override
    public final void handle(HttpExchange exchange) throws IOException {
        try {
            handle(exchange, exchange.getRequestURI().getPath());
        } catch (Exception e) {
            json(exchange, 500, error(String.valueOf(e)));
        } finally {
            exchange.close();
        }
    }

    static ObjectNode error(String message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", message);
        return node;
    }

    static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
