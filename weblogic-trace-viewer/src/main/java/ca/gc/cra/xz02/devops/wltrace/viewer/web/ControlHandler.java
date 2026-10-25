package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /api/control/start} with an optional {@code {"name": ...}} body starts a recording
 * and answers its id; {@code POST /api/control/stop} stops it and answers the ids the servers
 * reported. A start the state does not allow is a 409 with the reason.
 */
final class ControlHandler extends JsonHandler {

    static final String START = "/api/control/start";
    static final String STOP = "/api/control/stop";

    private final DomainControl control;

    ControlHandler(DomainControl control) {
        this.control = control;
    }

    @Override
    void handle(HttpExchange exchange, String path) throws Exception {
        if (!exchange.getRequestMethod().equals("POST")) {
            json(exchange, 405, error("POST"));
            return;
        }
        if (path.equals(START)) {
            start(exchange);
        } else if (path.equals(STOP)) {
            stop(exchange);
        } else {
            json(exchange, 404, error("not found"));
        }
    }

    private void start(HttpExchange exchange) throws Exception {
        String name = null;
        byte[] body = readAll(exchange.getRequestBody());
        if (body.length > 0) {
            JsonNode node = JSON.readTree(body);
            name = node.path("name").asText(null);
        }
        try {
            ObjectNode result = JSON.createObjectNode();
            result.put("id", control.startRecording(name));
            json(exchange, 200, result);
        } catch (IllegalStateException e) {
            json(exchange, 409, error(e.getMessage()));
        }
    }

    private void stop(HttpExchange exchange) throws Exception {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode stopped = result.putArray("stopped");
        control.stopRecording().forEach(stopped::add);
        json(exchange, 200, result);
    }
}
