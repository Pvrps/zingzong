package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex;
import ca.gc.cra.xz02.devops.wltrace.viewer.recording.Recordings;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The recordings under the output directory.
 * <pre>
 *   GET    /api/recordings                     every recording, newest first
 *   GET    /api/recordings/{id}                the index: merged manifest, trace summaries, lineage
 *   DELETE /api/recordings/{id}                remove a finished recording and its files (409 while active)
 *   GET    /api/recordings/{id}/traces/{file}  one trace file
 * </pre>
 */
final class RecordingsHandler extends JsonHandler {

    static final String ROOT = "/api/recordings";

    private final DomainControl control;
    private final Recordings recordings;

    RecordingsHandler(DomainControl control, Recordings recordings) {
        this.control = control;
        this.recordings = recordings;
    }

    @Override
    void handle(HttpExchange exchange, String path) throws Exception {
        String rest = path.substring(ROOT.length());
        if (rest.isEmpty() || rest.equals("/")) {
            json(exchange, 200, list());
            return;
        }
        String[] parts = rest.substring(1).split("/");
        String id = parts[0];
        if (!recordings.exists(id)) {
            json(exchange, 404, error("no recording " + id));
        } else if (parts.length == 1 && exchange.getRequestMethod().equals("DELETE")) {
            delete(exchange, id);
        } else if (parts.length == 1) {
            RecordingIndex index = recordings.index(id);
            json(exchange, 200, JSON.valueToTree(index));
        } else if (parts.length == 3 && parts[1].equals("traces")) {
            Path file = recordings.traceFile(id, parts[2]);
            if (file == null) {
                json(exchange, 404, error("no trace " + parts[2]));
            } else {
                send(exchange, 200, "application/json", Files.readAllBytes(file));
            }
        } else {
            json(exchange, 404, error("not found"));
        }
    }

    private void delete(HttpExchange exchange, String id) throws Exception {
        try {
            int files = recordings.delete(id, control.state().activeRecordingId);
            ObjectNode result = JSON.createObjectNode();
            result.put("deleted", id);
            result.put("files", files);
            json(exchange, 200, result);
        } catch (IllegalStateException e) {
            json(exchange, 409, error(e.getMessage()));
        }
    }

    private ArrayNode list() {
        String activeId = control.state().activeRecordingId;
        ArrayNode list = JSON.createArrayNode();
        for (Recordings.Summary summary : recordings.list(activeId)) {
            ObjectNode node = list.addObject();
            node.put("id", summary.id);
            node.put("name", summary.name);
            node.put("startedAt", summary.startedAt);
            node.put("endedAt", summary.endedAt);
            node.put("traceCount", summary.traceCount);
            node.put("active", summary.active);
            ArrayNode servers = node.putArray("servers");
            summary.servers.forEach(servers::add);
        }
        return list;
    }
}
