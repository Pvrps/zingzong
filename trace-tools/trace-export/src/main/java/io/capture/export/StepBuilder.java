package io.capture.export;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.capture.reader.Labels;
import io.capture.reader.RecordingDir;
import io.capture.reader.lineage.LineageDetector;
import io.capture.trace.LineageEntry;
import io.capture.trace.LineageFile;
import io.capture.trace.LineageRef;
import io.capture.trace.Span;
import io.capture.trace.TraceJson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps selected spans to {@link Step}s. Lookup, payload and expected result are copied from
 * the span as captured. Variables and substitutions come from lineage: an entry whose
 * consumer and producer are both selected becomes a variable on the producing step and a
 * substitution on the consuming one; when the detected producer is not selected but one of
 * its alternatives is, that alternative is used and the fact is kept on the substitution.
 */
public final class StepBuilder {

    private final LineageFile lineage;

    public StepBuilder(RecordingDir recording) {
        this(new LineageDetector().detect(recording.manifest.id, recording.traces));
    }

    public StepBuilder(LineageFile lineage) {
        this.lineage = lineage;
    }

    public List<Step> build(Selection selection) {
        List<Step> steps = new ArrayList<>();
        Map<String, Step> byKey = new HashMap<>();
        int index = 1;
        for (Selection.Selected selected : selection.spans()) {
            Step step = step(selected, index++);
            steps.add(step);
            byKey.put(selected.key(), step);
        }
        wire(selection, byKey);
        return steps;
    }

    private Step step(Selection.Selected selected, int index) {
        Span span = selected.span;
        Step step = new Step();
        step.index = index;
        step.id = "step" + index;
        step.name = Labels.span(span);
        step.traceId = selected.trace.id();
        step.spanId = span.id;
        step.lookup = new Step.Lookup();
        if (span.ejb != null) {
            step.kind = Step.KIND_EJB;
            step.lookup.jndiNames = span.ejb.jndiNames;
            step.lookup.interfaceName = span.ejb.interfaceName;
            step.lookup.method = span.ejb.method;
            step.lookup.signature = span.ejb.signature;
            step.lookup.invocation = span.ejb.invocation;
            step.payload = span.args == null ? null : TraceJson.mapper().valueToTree(span.args);
            step.expectedResult = span.result == null ? null : TraceJson.mapper().valueToTree(span.result);
        } else if (span.http != null && span.layer != null) {
            step.kind = Step.KIND_HTTP;
            step.lookup.interfaceName = span.http.resourceClass;
            step.lookup.method = span.http.resourceMethod;
            step.lookup.signature = span.http.resourceSignature;
            step.payload = span.args == null ? null : TraceJson.mapper().valueToTree(span.args);
            step.expectedResult = span.result == null ? null : TraceJson.mapper().valueToTree(span.result);
        } else if (span.http != null) {
            step.kind = Step.KIND_HTTP;
            step.lookup.httpMethod = span.http.method;
            step.lookup.path = span.http.path;
            step.lookup.query = span.http.query;
            step.lookup.headers = span.http.requestHeaders;
            step.lookup.contentType = span.http.contentType;
            step.lookup.bodyEncoding = span.http.requestBodyEncoding;
            step.payload = span.http.requestBody == null ? null : TraceJson.mapper().getNodeFactory().textNode(span.http.requestBody);
            ObjectNode response = TraceJson.mapper().createObjectNode();
            response.put("status", span.http.status);
            response.put("contentType", span.http.responseContentType);
            response.set("headers", TraceJson.mapper().valueToTree(span.http.responseHeaders));
            response.put("body", span.http.responseBody);
            response.put("bodyEncoding", span.http.responseBodyEncoding);
            step.expectedResult = response;
        } else {
            step.kind = span.kind;
        }
        step.expectedException = span.exception;
        return step;
    }

    /** Variables and substitutions between the selected steps. */
    private void wire(Selection selection, Map<String, Step> byKey) {
        Map<String, Step.Variable> variables = new LinkedHashMap<>();
        Map<String, Integer> namesUsed = new HashMap<>();
        for (LineageEntry entry : lineage.entries) {
            if (entry.from == null || entry.to == null || entry.to.trace == null) {
                continue;
            }
            Step consumer = byKey.get(entry.to.trace + ":" + entry.to.span);
            if (consumer == null) {
                continue;
            }
            LineageRef producerRef = selection.contains(entry.from.trace, entry.from.span) ? entry.from : null;
            List<String> alternatives = new ArrayList<>();
            if (entry.alternatives != null) {
                for (LineageRef alt : entry.alternatives) {
                    if (selection.contains(alt.trace, alt.span)) {
                        if (producerRef == null) {
                            producerRef = alt;
                        } else {
                            alternatives.add(byKey.get(alt.trace + ":" + alt.span).id + "/" + alt.path);
                        }
                    }
                }
            }
            if (producerRef == null) {
                continue;
            }
            Step producer = byKey.get(producerRef.trace + ":" + producerRef.span);
            if (producer.index >= consumer.index) {
                continue;
            }
            String variableKey = producer.id + "/" + producerRef.path;
            Step.Variable variable = variables.get(variableKey);
            if (variable == null) {
                variable = new Step.Variable();
                variable.name = uniqueName(preferredName(entry.to.path, producerRef.path), namesUsed);
                variable.path = producerRef.path;
                variable.capturedValue = entry.value;
                variables.put(variableKey, variable);
                producer.variables.add(variable);
            }
            if (!variable.usedBy.contains(consumer.id)) {
                variable.usedBy.add(consumer.id);
            }
            Step.Substitution substitution = new Step.Substitution();
            substitution.path = entry.to.path;
            substitution.variable = variable.name;
            substitution.fromStep = producer.id;
            substitution.fromPath = producerRef.path;
            substitution.capturedValue = entry.value;
            substitution.alternatives = alternatives.isEmpty() ? null : alternatives;
            substitution.alsoProducedHere = entry.alsoProducedHere;
            consumer.substitutions.add(substitution);
        }
    }

    /** The consuming field's name ("orderId" from "args[0].orderId"), else the producing one's, else "value". */
    static String preferredName(String consumerPath, String producerPath) {
        String name = lastName(consumerPath);
        if (name == null) {
            name = lastName(producerPath);
        }
        return name == null ? "value" : name;
    }

    private static String lastName(String path) {
        int dot = path.lastIndexOf('.');
        String last = dot < 0 ? path : path.substring(dot + 1);
        if (last.endsWith("]") || last.equals("args") || last.equals("result") || last.isEmpty()) {
            return null;
        }
        return last;
    }

    private static String uniqueName(String base, Map<String, Integer> used) {
        Integer n = used.get(base);
        used.put(base, n == null ? 1 : n + 1);
        return n == null ? base : base + (n + 1);
    }
}
