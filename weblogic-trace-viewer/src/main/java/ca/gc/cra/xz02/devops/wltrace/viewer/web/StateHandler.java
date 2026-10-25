package ca.gc.cra.xz02.devops.wltrace.viewer.web;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;

/** {@code GET /api/state}: the {@link ControlState} as JSON, re-evaluated on every call. */
final class StateHandler extends JsonHandler {

    private final DomainControl control;
    private final Path recordings;

    StateHandler(DomainControl control, Path recordings) {
        this.control = control;
        this.recordings = recordings;
    }

    @Override
    void handle(HttpExchange exchange, String path) throws Exception {
        json(exchange, 200, toJson(control.state()));
    }

    ObjectNode toJson(ControlState state) {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", state.kind);
        node.put("message", state.message);
        node.put("ready", state.ready);
        node.put("enabledOnDisk", state.enabledOnDisk);
        node.put("blockPresent", state.blockPresent);
        node.put("jarPresent", state.jarPresent);
        node.put("jarCurrent", state.jarCurrent);
        node.put("domain", state.domain);
        node.put("webLogicVersion", state.webLogicVersion);
        node.put("outDir", recordings.toString());
        node.put("pluginAgentBuild", state.pluginAgentBuild);
        node.put("activeRecordingId", state.activeRecordingId);
        node.put("activeRecordingName", state.activeRecordingName);
        ArrayNode servers = node.putArray("servers");
        for (ControlState.Server server : state.servers) {
            ObjectNode serverNode = servers.addObject();
            serverNode.put("pid", server.pid);
            serverNode.put("serverName", server.serverName);
            serverNode.put("agentLoaded", server.agentLoaded);
            serverNode.put("agentBuild", server.agentBuild);
            serverNode.put("agentCurrent", server.agentCurrent);
            serverNode.put("recording", server.recording);
            serverNode.put("recordingId", server.recordingId);
            serverNode.put("recordingName", server.recordingName);
            serverNode.put("traceCount", server.traceCount);
            serverNode.put("droppedTraces", server.droppedTraces);
        }
        return node;
    }
}
