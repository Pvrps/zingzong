package io.capture.export;

import io.capture.reader.LoadedTrace;
import io.capture.reader.RecordingDir;
import io.capture.trace.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The spans to export, in recording order. Span ids are per trace, so a selected span is
 * named {@code <trace seq>:<span id>} (e.g. {@code 1:s3}) or {@code <trace id>:<span id>};
 * a bare {@code s3} is accepted when the recording has one trace.
 */
public final class Selection {

    /** A selected span with its trace. */
    public static final class Selected {
        public final LoadedTrace trace;
        public final Span span;

        Selected(LoadedTrace trace, Span span) {
            this.trace = trace;
            this.span = span;
        }

        public String key() {
            return trace.id() + ":" + span.id;
        }
    }

    private final List<Selected> spans;

    private Selection(List<Selected> spans) {
        this.spans = Collections.unmodifiableList(spans);
    }

    /** Selected spans in recording order (trace seq, then span order). */
    public List<Selected> spans() {
        return spans;
    }

    public boolean contains(String traceId, String spanId) {
        for (Selected s : spans) {
            if (s.trace.id().equals(traceId) && s.span.id.equals(spanId)) {
                return true;
            }
        }
        return false;
    }

    public static Selection of(RecordingDir recording, Set<String> ids) {
        List<Selected> found = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>(ids);
        for (LoadedTrace trace : recording.traces) {
            for (Span span : trace.trace.spans) {
                for (String id : ids) {
                    if (matches(recording, trace, span, id)) {
                        found.add(new Selected(trace, span));
                        unresolved.remove(id);
                    }
                }
            }
        }
        if (!unresolved.isEmpty()) {
            throw new IllegalArgumentException("no such span(s): " + unresolved + " (use <trace seq>:<span id>, e.g. 1:s3)");
        }
        return new Selection(found);
    }

    private static boolean matches(RecordingDir recording, LoadedTrace trace, Span span, String id) {
        int colon = id.indexOf(':');
        if (colon < 0) {
            return recording.traces.size() == 1 && span.id.equals(id);
        }
        String tracePart = id.substring(0, colon);
        String spanPart = id.substring(colon + 1);
        if (!span.id.equals(spanPart)) {
            return false;
        }
        return trace.id().equals(tracePart) || String.valueOf(trace.seq()).equals(tracePart);
    }
}
