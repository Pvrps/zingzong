package io.capture.reader;

import io.capture.reader.lineage.LineageDetector;
import io.capture.trace.LineageFile;
import io.capture.trace.RecordingIndex;
import io.capture.trace.Span;

/** Builds {@code recording-index.json}: manifest, trace summaries, sessions, lineage. */
public final class Indexer {

    private Indexer() {
    }

    public static RecordingIndex index(RecordingDir recording) {
        return index(recording, new LineageDetector().detect(recording.manifest.id, recording.traces));
    }

    public static RecordingIndex index(RecordingDir recording, LineageFile lineage) {
        RecordingIndex index = new RecordingIndex();
        index.recording = recording.manifest;
        for (LoadedTrace loaded : recording.traces) {
            index.traces.add(summary(loaded));
        }
        index.sessions = SessionGrouper.group(recording.traces);
        index.lineage = lineage.entries;
        return index;
    }

    static RecordingIndex.TraceSummary summary(LoadedTrace loaded) {
        RecordingIndex.TraceSummary s = new RecordingIndex.TraceSummary();
        s.id = loaded.id();
        s.seq = loaded.seq();
        s.file = loaded.file.getFileName().toString();
        s.startedAt = loaded.trace.trace.startedAt;
        s.durationMs = loaded.trace.trace.durationMs;
        s.principal = loaded.trace.trace.principal;
        s.httpSessionId = loaded.trace.trace.httpSessionId;
        s.parentTraceId = loaded.trace.trace.parentTraceId;
        Span root = loaded.root();
        s.rootKind = root == null ? null : root.kind;
        s.rootLabel = Labels.span(root);
        s.spanCount = loaded.trace.spans.size();
        int warnings = 0;
        boolean failed = false;
        for (Span span : loaded.trace.spans) {
            if (span.serializationWarnings != null) {
                warnings += span.serializationWarnings.size();
            }
            if (span.exception != null && span.parentId == null) {
                failed = true;
            }
        }
        s.warningCount = warnings;
        s.failed = failed;
        return s;
    }
}
