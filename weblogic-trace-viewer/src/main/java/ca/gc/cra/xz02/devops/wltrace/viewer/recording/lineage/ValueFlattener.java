package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import ca.gc.cra.xz02.devops.wltrace.model.ValueCapture;
import ca.gc.cra.xz02.devops.wltrace.viewer.recording.LoadedTrace;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the inputs and outputs of a trace's spans into {@link ValueLocation}s. Outputs are span
 * results and root response bodies; inputs are arguments, request bodies, URL path segments and
 * query parameters. A JSON or XML body is walked element by element and is also one value as a
 * whole. Marker objects are annotation, not payload, and are skipped.
 */
public final class ValueFlattener {

    private static final Set<String> VALUE_MARKERS = new HashSet<>(Arrays.asList(
            "@ref", "@lazy", "@handle", "@unserializable", "@truncated", "@text", "@bytes"));

    private ValueFlattener() {
    }

    public static List<ValueLocation> outputs(LoadedTrace loaded) {
        List<ValueLocation> locations = new ArrayList<>();
        for (Span span : loaded.trace.spans) {
            if (span.result != null) {
                flatten(loaded, span, "result", span.result.value, locations);
            }
            if (span.http != null && span.http.responseBody != null) {
                body(loaded, span, "http.responseBody", span.http.responseBody, locations);
            }
        }
        return locations;
    }

    public static List<ValueLocation> inputs(LoadedTrace loaded) {
        List<ValueLocation> locations = new ArrayList<>();
        for (Span span : loaded.trace.spans) {
            if (span.args != null) {
                for (int index = 0; index < span.args.size(); index++) {
                    ValueCapture argument = span.args.get(index);
                    flatten(loaded, span, "args[" + index + "]", argument == null ? null : argument.value, locations);
                }
            }
            if (span.http != null && span.http.requestBody != null) {
                body(loaded, span, "http.requestBody", span.http.requestBody, locations);
            }
            if (span.http != null && span.layer == null) {
                urlValues(loaded, span, locations);
            }
        }
        return locations;
    }

    /** The URL path segments ({@code /orders/api/orders/{id}/confirm}) and query parameters of a root request. */
    private static void urlValues(LoadedTrace loaded, Span span, List<ValueLocation> locations) {
        if (span.http.path != null) {
            String[] segments = span.http.path.split("/");
            for (int index = 0; index < segments.length; index++) {
                if (!segments[index].isEmpty()) {
                    add(loaded, span, "http.path[" + index + "]", segments[index], locations);
                }
            }
        }
        if (span.http.query != null) {
            for (String pair : span.http.query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    add(loaded, span, "http.query." + pair.substring(0, equals), pair.substring(equals + 1), locations);
                }
            }
        }
    }

    private static void body(LoadedTrace loaded, Span span, String path, String text, List<ValueLocation> locations) {
        JsonNode json = parseJson(text);
        if (json != null) {
            flatten(loaded, span, path, json, locations);
        } else {
            JsonNode xml = XmlBodies.toTree(text);
            if (xml != null) {
                flatten(loaded, span, path, xml, locations);
            }
        }
        add(loaded, span, path, text, locations);
    }

    static void flatten(LoadedTrace loaded, Span span, String path, JsonNode node, List<ValueLocation> into) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if (isMarker(node)) {
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!field.getKey().startsWith("@")) {
                    flatten(loaded, span, path + "." + field.getKey(), field.getValue(), into);
                }
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                flatten(loaded, span, path + "[" + index + "]", node.get(index), into);
            }
            return;
        }
        String normalized = DistinctiveValues.normalize(node, path);
        if (normalized != null) {
            into.add(location(loaded, span, path, normalized));
        }
    }

    /** A marker object stands for a value that is not written as it was; its fields describe the marker. */
    static boolean isMarker(JsonNode node) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (VALUE_MARKERS.contains(names.next())) {
                return true;
            }
        }
        return false;
    }

    private static void add(LoadedTrace loaded, Span span, String path, String text, List<ValueLocation> into) {
        String normalized = DistinctiveValues.normalize(TraceJson.mapper().getNodeFactory().textNode(text), path);
        if (normalized != null) {
            into.add(location(loaded, span, path, normalized));
        }
    }

    private static ValueLocation location(LoadedTrace loaded, Span span, String path, String normalized) {
        return new ValueLocation(loaded.index, loaded.id(), loaded.sequence(), span.id, span.order, path, normalized, span.parentId == null);
    }

    private static JsonNode parseJson(String text) {
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            return TraceJson.mapper().readTree(trimmed);
        } catch (Exception notJson) {
            return null;
        }
    }
}
