package ca.gc.cra.xz02.devops.wltrace.plugin.reader.lineage;

import ca.gc.cra.xz02.devops.wltrace.plugin.reader.LoadedTrace;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingRef;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceInfo;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;
import ca.gc.cra.xz02.devops.wltrace.model.ValueCapture;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/** Hand-built traces for the lineage tests. */
final class Traces {

    private Traces() {
    }

    static LoadedTrace trace(int index, String id, Span... spans) {
        Trace trace = new Trace();
        trace.recording = new RecordingRef();
        trace.recording.id = "rec-test";
        trace.recording.seq = index + 1;
        trace.trace = new TraceInfo();
        trace.trace.id = id;
        trace.trace.startedAt = "2026-10-23T18:00:00.000Z";
        trace.spans = new ArrayList<>(Arrays.asList(spans));
        return new LoadedTrace(Paths.get(String.format("%06d-%s.json", index + 1, id)), trace, index);
    }

    /** A span with JSON args and result; parentId null makes it the root. */
    static Span span(String id, String parentId, int order, String bean, String method, String argsJson, String resultJson) {
        Span span = new Span();
        span.id = id;
        span.parentId = parentId;
        span.depth = parentId == null ? 0 : 1;
        span.order = order;
        span.kind = Span.KIND_EJB;
        span.ejb = new EjbInfo();
        span.ejb.beanClass = "com.acme." + bean + "Bean";
        span.ejb.method = method;
        try {
            if (argsJson != null) {
                span.args = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode arg : TraceJson.mapper().readTree(argsJson)) {
                    ValueCapture capture = new ValueCapture();
                    capture.declaredType = "java.lang.Object";
                    capture.value = arg;
                    span.args.add(capture);
                }
            }
            if (resultJson != null) {
                span.result = new ValueCapture();
                span.result.declaredType = "java.lang.Object";
                span.result.value = TraceJson.mapper().readTree(resultJson);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return span;
    }
}
