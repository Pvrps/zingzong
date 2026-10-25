package ca.gc.cra.xz02.devops.wltrace.plugin.reader.lineage;

import ca.gc.cra.xz02.devops.wltrace.plugin.reader.LoadedTrace;
import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.LineageRef;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds values in later inputs that came from earlier outputs. Annotates, never removes:
 * every distinctive input value ends up either sourced or classified.
 *
 * Traces are walked in seq order. Every distinctive scalar in a span's {@code result} (and a
 * root HTTP span's response body) goes into a source index keyed by its normalized form;
 * every distinctive scalar in a later trace's {@code args} (and HTTP request body) is looked
 * up there. A hit is a {@link LineageEntry} {@code to ← from}; the nearest earlier source
 * wins, the others stay as alternatives.
 *
 * The frequency cap applies to the source index only: a value that is produced as a result
 * in more than {@code frequencyCap} distinct traces is too common to be a meaningful source
 * (an echoed constant, a status code), so it is not indexed. It never affects
 * classification.
 *
 * A value's <em>origin</em> is where it first appears in seq order: in a root span's input it
 * is user-originated, anywhere else (a result, a nested span's input) system-originated.
 * User-originated values are never linked: when they show up again later they were typed
 * again, and the results that echoed them in between are not their source. Only
 * system-originated values (ids, computed amounts, rows read back) are looked up in the index.
 *
 * Input values without a source are classified: {@code environment} when they are an
 * instant inside the trace's own time window (the application generated them during the
 * call) or appear only in nested spans (read from the database or computed), else
 * {@code userInput} when they appear in the root span's input. An entry whose value is also
 * produced by the same trace's own results is flagged {@code alsoProducedHere}: it may be a
 * recomputation rather than a flow, and that cannot be told apart from the trace alone.
 */
public final class LineageDetector {

    public static final int DEFAULT_FREQUENCY_CAP = 5;
    /** Slack around the trace window: the request layer stamps times slightly before the root span opens. */
    public static final Duration WINDOW_SLACK = Duration.ofSeconds(2);

    private final int frequencyCap;

    public LineageDetector() {
        this(DEFAULT_FREQUENCY_CAP);
    }

    public LineageDetector(int frequencyCap) {
        this.frequencyCap = frequencyCap;
    }

    public LineageFile detect(String recordingId, List<LoadedTrace> traces) {
        List<List<ValueRef>> resultsByTrace = new ArrayList<>();
        List<List<ValueRef>> argsByTrace = new ArrayList<>();
        Map<String, Set<Integer>> producingTraces = new HashMap<>();
        for (LoadedTrace trace : traces) {
            List<ValueRef> results = ValueFlattener.results(trace);
            resultsByTrace.add(results);
            argsByTrace.add(ValueFlattener.args(trace));
            for (ValueRef r : results) {
                producingTraces.computeIfAbsent(r.value, k -> new HashSet<>()).add(trace.index);
            }
        }

        LineageFile file = new LineageFile();
        file.recordingId = recordingId;
        file.generatedAt = Instant.now().toString();
        List<LineageEntry> unsourced = new ArrayList<>();

        Map<String, List<ValueRef>> index = new HashMap<>();
        Set<String> userOriginated = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int m = 0; m < traces.size(); m++) {
            LoadedTrace trace = traces.get(m);
            Set<String> rootInputs = new HashSet<>();
            Set<String> producedHere = new HashSet<>();
            for (ValueRef r : resultsByTrace.get(m)) {
                producedHere.add(r.value);
            }
            for (ValueRef a : argsByTrace.get(m)) {
                if (a.rootSpan) {
                    rootInputs.add(a.value);
                    if (seen.add(a.value)) {
                        userOriginated.add(a.value);
                    }
                }
            }
            Set<String> classified = new HashSet<>();
            for (ValueRef target : argsByTrace.get(m)) {
                seen.add(target.value);
                List<ValueRef> sources = userOriginated.contains(target.value) ? null : index.get(target.value);
                if (sources != null && !sources.isEmpty()) {
                    LineageEntry entry = sourced(target, sources);
                    if (producedHere.contains(target.value)) {
                        entry.alsoProducedHere = Boolean.TRUE;
                    }
                    file.entries.add(entry);
                } else if (classified.add(target.value)) {
                    unsourced.add(unsourced(target, classify(target, rootInputs, trace)));
                }
            }
            // Results of trace m become sources for later traces; nearest first means last added first.
            for (ValueRef r : resultsByTrace.get(m)) {
                seen.add(r.value);
                if (producingTraces.getOrDefault(r.value, Collections.<Integer>emptySet()).size() > frequencyCap) {
                    continue;
                }
                index.computeIfAbsent(r.value, k -> new ArrayList<>()).add(0, r);
            }
        }
        file.entries.addAll(unsourced);
        return file;
    }

    private String classify(ValueRef target, Set<String> rootInputs, LoadedTrace trace) {
        if (!rootInputs.contains(target.value)) {
            return LineageEntry.CLASS_ENVIRONMENT;
        }
        return insideTraceWindow(target.value, trace) ? LineageEntry.CLASS_ENVIRONMENT : LineageEntry.CLASS_USER_INPUT;
    }

    /** An instant stamped while the call ran was generated by the application, not typed by a person. */
    static boolean insideTraceWindow(String value, LoadedTrace trace) {
        if (value.indexOf('T') < 0 || trace.trace.trace.startedAt == null) {
            return false;
        }
        try {
            Instant candidate = Instant.parse(value);
            Instant start = Instant.parse(trace.trace.trace.startedAt);
            Instant end = start.plusMillis(Math.max(0, trace.trace.trace.durationMs));
            return !candidate.isBefore(start.minus(WINDOW_SLACK)) && !candidate.isAfter(end.plus(WINDOW_SLACK));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static LineageEntry sourced(ValueRef target, List<ValueRef> sources) {
        LineageEntry entry = new LineageEntry();
        entry.to = ref(target);
        entry.from = ref(sources.get(0));
        entry.value = target.value;
        if (sources.size() > 1) {
            entry.alternatives = new ArrayList<>();
            for (ValueRef alt : sources.subList(1, sources.size())) {
                entry.alternatives.add(ref(alt));
            }
        }
        return entry;
    }

    private static LineageEntry unsourced(ValueRef target, String classification) {
        LineageEntry entry = new LineageEntry();
        entry.to = ref(target);
        entry.value = target.value;
        entry.classification = classification;
        return entry;
    }

    private static LineageRef ref(ValueRef v) {
        return new LineageRef(v.traceId, v.spanId, v.path);
    }
}
