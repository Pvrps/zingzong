package io.capture.reader.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import io.capture.reader.LoadedTrace;
import io.capture.trace.Span;
import io.capture.trace.ValueCapture;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Turns the args and results of a trace's spans into {@link ValueRef} triples. */
public final class ValueFlattener {

    private ValueFlattener() {
    }

    public static List<ValueRef> results(LoadedTrace loaded) {
        List<ValueRef> refs = new ArrayList<>();
        for (Span span : loaded.trace.spans) {
            if (span.result != null) {
                flatten(loaded, span, "result", span.result.value, refs);
            }
            if (span.http != null && span.http.responseBody != null) {
                JsonNode body = parseJson(span.http.responseBody);
                if (body != null) {
                    flatten(loaded, span, "http.responseBody", body, refs);
                } else {
                    add(loaded, span, "http.responseBody", span.http.responseBody, refs);
                }
            }
        }
        return refs;
    }

    public static List<ValueRef> args(LoadedTrace loaded) {
        List<ValueRef> refs = new ArrayList<>();
        for (Span span : loaded.trace.spans) {
            if (span.args != null) {
                for (int i = 0; i < span.args.size(); i++) {
                    ValueCapture arg = span.args.get(i);
                    flatten(loaded, span, "args[" + i + "]", arg == null ? null : arg.value, refs);
                }
            }
            if (span.http != null && span.http.requestBody != null) {
                // Phase 5: the HTTP request body counts as input. JSON bodies are walked;
                // other bodies contribute the whole text as one value.
                JsonNode body = parseJson(span.http.requestBody);
                if (body != null) {
                    flatten(loaded, span, "http.requestBody", body, refs);
                } else {
                    add(loaded, span, "http.requestBody", span.http.requestBody, refs);
                }
            }
        }
        return refs;
    }

    static void flatten(LoadedTrace loaded, Span span, String path, JsonNode node, List<ValueRef> into) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if (isMarker(node)) {
                return; // @ref, @lazy, @handle, @unserializable, @truncated, @text, @bytes: annotation, not payload
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (e.getKey().startsWith("@")) {
                    continue;
                }
                flatten(loaded, span, path + "." + e.getKey(), e.getValue(), into);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(loaded, span, path + "[" + i + "]", node.get(i), into);
            }
            return;
        }
        String normalized = Distinctive.normalize(node, path);
        if (normalized != null) {
            into.add(new ValueRef(loaded.index, loaded.id(), loaded.seq(), span.id, span.order, path, normalized, span.parentId == null));
        }
    }

    /** A marker object stands for a value that is not written as it was; its fields describe the marker. */
    static boolean isMarker(JsonNode node) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (n.equals("@ref") || n.equals("@lazy") || n.equals("@handle") || n.equals("@unserializable")
                    || n.equals("@truncated") || n.equals("@text") || n.equals("@bytes")) {
                return true;
            }
        }
        return false;
    }

    private static void add(LoadedTrace loaded, Span span, String path, String text, List<ValueRef> into) {
        String normalized = Distinctive.normalize(io.capture.trace.TraceJson.mapper().getNodeFactory().textNode(text), path);
        if (normalized != null) {
            into.add(new ValueRef(loaded.index, loaded.id(), loaded.seq(), span.id, span.order, path, normalized, span.parentId == null));
        }
    }

    private static JsonNode parseJson(String text) {
        String t = text.trim();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            return null;
        }
        try {
            return io.capture.trace.TraceJson.mapper().readTree(t);
        } catch (Exception e) {
            return null;
        }
    }
}
