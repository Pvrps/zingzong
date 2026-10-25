package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector;

/** Builds the index the page loads for a recording: the merged manifest, one summary per trace, and the lineage entries. */
public final class RecordingIndexer {

    private final LineageDetector lineage;

    public RecordingIndexer(LineageDetector lineage) {
        this.lineage = lineage;
    }

    public RecordingIndex index(RecordingDirectory recording) {
        LineageFile detected = lineage.detect(recording.manifest.id, recording.traces);
        RecordingIndex index = new RecordingIndex();
        index.recording = recording.manifest;
        recording.traces.forEach(loaded -> index.traces.add(summary(loaded)));
        index.lineage = detected.entries;
        return index;
    }

    static RecordingIndex.TraceSummary summary(LoadedTrace loaded) {
        RecordingIndex.TraceSummary summary = new RecordingIndex.TraceSummary();
        summary.id = loaded.id();
        summary.seq = loaded.sequence();
        summary.file = loaded.file.getFileName().toString();
        summary.startedAt = loaded.trace.trace.startedAt;
        summary.durationMs = loaded.trace.trace.durationMs;
        summary.principal = loaded.trace.trace.principal;
        summary.httpSessionId = loaded.trace.trace.httpSessionId;
        summary.parentTraceId = loaded.trace.trace.parentTraceId;
        summary.parentSpanId = loaded.trace.trace.parentSpanId;
        Span root = loaded.root();
        summary.rootKind = root == null ? null : root.kind;
        summary.rootLabel = SpanLabels.span(root);
        summary.spanCount = loaded.trace.spans.size();
        summary.warningCount = loaded.trace.spans.stream()
                .mapToInt(span -> span.serializationWarnings == null ? 0 : span.serializationWarnings.size())
                .sum();
        summary.failed = loaded.trace.spans.stream().anyMatch(span -> span.exception != null && span.parentId == null);
        return summary;
    }
}
