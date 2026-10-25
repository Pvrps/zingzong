package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;

import java.nio.file.Path;
import java.util.Optional;

/** A trace together with the file it came from and its position in the recording's order. */
public final class LoadedTrace {

    public final Path file;
    public final Trace trace;
    /** 0-based position after ordering by start time and sequence. */
    public final int index;

    public LoadedTrace(Path file, Trace trace, int index) {
        this.file = file;
        this.trace = trace;
        this.index = index;
    }

    public String id() {
        return trace.trace.id;
    }

    public long sequence() {
        return trace.recording == null ? 0 : trace.recording.seq;
    }

    /** The span without a parent, else the first span; null for an empty trace. */
    public Span root() {
        return trace.spans.stream().filter(span -> span.parentId == null).findFirst()
                .orElse(trace.spans.isEmpty() ? null : trace.spans.get(0));
    }

    public Optional<Span> span(String id) {
        return trace.spans.stream().filter(span -> span.id.equals(id)).findFirst();
    }
}
