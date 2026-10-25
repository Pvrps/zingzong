package io.capture.plugin.reader;

import io.capture.trace.Span;
import io.capture.trace.Trace;

import java.nio.file.Path;

/** A trace together with where it came from and its position in the recording. */
public final class LoadedTrace {

    public final Path file;
    public final Trace trace;
    /** 0-based position after ordering by seq. */
    public final int index;

    public LoadedTrace(Path file, Trace trace, int index) {
        this.file = file;
        this.trace = trace;
        this.index = index;
    }

    public String id() {
        return trace.trace.id;
    }

    public long seq() {
        return trace.recording == null ? 0 : trace.recording.seq;
    }

    public Span root() {
        for (Span span : trace.spans) {
            if (span.parentId == null) {
                return span;
            }
        }
        return trace.spans.isEmpty() ? null : trace.spans.get(0);
    }

    public Span span(String id) {
        for (Span span : trace.spans) {
            if (span.id.equals(id)) {
                return span;
            }
        }
        return null;
    }
}
