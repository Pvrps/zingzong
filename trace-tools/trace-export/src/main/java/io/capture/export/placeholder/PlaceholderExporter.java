package io.capture.export.placeholder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.capture.export.Exporter;
import io.capture.export.Selection;
import io.capture.export.Step;
import io.capture.export.StepBuilder;
import io.capture.reader.RecordingDir;
import io.capture.trace.TraceJson;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Writes the steps of one recording as a YAML file with {@code ${variable}} placeholders.
 * This is not the work format: it exists to show that a {@link Step} carries everything a
 * step needs. Per step: the lookup, the payload with the substituted fields replaced by
 * {@code ${var}} (the captured value kept next to each substitution), the expected result as
 * captured, the variables to extract. An HTTP body is kept raw; when a substitution lands
 * inside a JSON body a {@code bodyWithPlaceholders} is added beside it.
 */
public final class PlaceholderExporter implements Exporter {

    public static final String FILE_NAME = "steps.yaml";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE));

    @Override
    public List<Step> export(RecordingDir recording, Selection selection) {
        return new StepBuilder(recording).build(selection);
    }

    /** Exports and writes {@code steps.yaml} into the recording directory; returns the steps. */
    public List<Step> write(RecordingDir recording, Selection selection) throws IOException {
        List<Step> steps = export(recording, selection);
        try (OutputStream out = Files.newOutputStream(recording.dir.resolve(FILE_NAME))) {
            YAML.writeValue(out, document(recording, steps));
        }
        return steps;
    }

    public static String toYaml(RecordingDir recording, List<Step> steps) throws IOException {
        return YAML.writeValueAsString(document(recording, steps));
    }

    static ObjectNode document(RecordingDir recording, List<Step> steps) {
        ObjectMapper json = TraceJson.mapper();
        ObjectNode doc = json.createObjectNode();
        ObjectNode rec = doc.putObject("recording");
        rec.put("id", recording.manifest.id);
        rec.put("name", recording.manifest.name);
        rec.put("exportedAt", Instant.now().toString());
        rec.put("format", "placeholder; ${name} refers to a variable extracted by an earlier step");
        ArrayNode list = doc.putArray("steps");
        for (Step step : steps) {
            ObjectNode node = json.valueToTree(step);
            node.remove("index");
            if (step.payload != null) {
                node.set("payload", withPlaceholders(step, json));
            }
            if (step.lookup != null && step.lookup.path != null) {
                String path = pathWithPlaceholders(step);
                if (path != null) {
                    ((ObjectNode) node.get("lookup")).put("pathWithPlaceholders", path);
                }
            }
            if (!step.variables.isEmpty()) {
                node.set("extract", node.remove("variables"));
            } else {
                node.remove("variables");
            }
            if (step.substitutions.isEmpty()) {
                node.remove("substitutions");
            }
            list.add(node);
        }
        return doc;
    }

    /** The payload with each substituted field replaced by {@code ${variable}}. */
    static JsonNode withPlaceholders(Step step, ObjectMapper json) {
        JsonNode payload = step.payload.deepCopy();
        if (step.substitutions.isEmpty()) {
            return payload;
        }
        if (payload.isTextual()) {
            // An HTTP body: kept raw; a JSON body gets a second rendering with placeholders.
            JsonNode body;
            try {
                body = json.readTree(payload.asText());
            } catch (IOException e) {
                return payload;
            }
            boolean any = false;
            for (Step.Substitution s : step.substitutions) {
                any |= replace(body, stripPrefix(s.path, "http.requestBody"), s.variable);
            }
            if (!any) {
                return payload;
            }
            ObjectNode both = json.createObjectNode();
            both.set("body", payload);
            both.put("bodyWithPlaceholders", body.toString());
            return both;
        }
        for (Step.Substitution s : step.substitutions) {
            if (s.path.startsWith("args")) {
                replace(payload, argsPath(s.path), s.variable);
            }
        }
        return payload;
    }

    /** The request path with substituted segments ("http.path[4]") replaced; null when none. */
    static String pathWithPlaceholders(Step step) {
        String[] segments = step.lookup.path.split("/", -1);
        boolean any = false;
        for (Step.Substitution s : step.substitutions) {
            if (s.path.startsWith("http.path[")) {
                int index = Integer.parseInt(s.path.substring("http.path[".length(), s.path.length() - 1));
                if (index < segments.length) {
                    segments[index] = "${" + s.variable + "}";
                    any = true;
                }
            }
        }
        return any ? String.join("/", segments) : null;
    }

    /** "args[0].orderId" → "[0].value.orderId": the captured arg wraps its value. */
    static String argsPath(String lineagePath) {
        String rest = lineagePath.substring("args".length());
        int close = rest.indexOf(']');
        if (close < 0) {
            return rest;
        }
        return rest.substring(0, close + 1) + ".value" + rest.substring(close + 1);
    }

    private static String stripPrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /** Sets the node at a dotted/indexed path to "${variable}". False when the path does not exist. */
    static boolean replace(JsonNode root, String path, String variable) {
        List<String> tokens = PathTokens.tokenize(path);
        if (tokens.isEmpty()) {
            return false;
        }
        JsonNode parent = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            parent = PathTokens.child(parent, tokens.get(i));
            if (parent == null) {
                return false;
            }
        }
        String last = tokens.get(tokens.size() - 1);
        if (last.startsWith("[") && parent.isArray()) {
            int index = Integer.parseInt(last.substring(1, last.length() - 1));
            if (index >= parent.size()) {
                return false;
            }
            ((ArrayNode) parent).set(index, ((ArrayNode) parent).textNode("${" + variable + "}"));
            return true;
        }
        if (parent.isObject() && parent.has(last)) {
            ((ObjectNode) parent).put(last, "${" + variable + "}");
            return true;
        }
        return false;
    }
}
