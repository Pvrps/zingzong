package io.capture.reader.lineage;

import io.capture.reader.LoadedTrace;
import io.capture.trace.LineageEntry;
import io.capture.trace.LineageFile;
import io.capture.trace.LineageRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds values in later inputs that came from earlier outputs.
 *
 * Traces are walked in seq order. Every distinctive scalar in a span's {@code result} goes
 * into an index keyed by its normalized form; every distinctive scalar in a later trace's
 * {@code args} (and HTTP request body) is looked up there. A hit is a {@link LineageEntry}
 * {@code to ← from}; the nearest earlier source wins, the others stay as alternatives.
 * Values that occur more than {@code frequencyCap} times anywhere in the recording are
 * ignored as noise. Input values without a source are classified {@code userInput} when
 * they appear in the root span's input of their trace, {@code environment} otherwise.
 */
public final class LineageDetector {

    public static final int DEFAULT_FREQUENCY_CAP = 5;

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
        Map<String, Integer> frequency = new HashMap<>();
        for (LoadedTrace trace : traces) {
            List<ValueRef> results = ValueFlattener.results(trace);
            List<ValueRef> args = ValueFlattener.args(trace);
            resultsByTrace.add(results);
            argsByTrace.add(args);
            for (ValueRef r : results) {
                frequency.merge(r.value, 1, Integer::sum);
            }
            for (ValueRef a : args) {
                frequency.merge(a.value, 1, Integer::sum);
            }
        }

        LineageFile file = new LineageFile();
        file.recordingId = recordingId;
        file.generatedAt = Instant.now().toString();
        List<LineageEntry> unsourced = new ArrayList<>();

        Map<String, List<ValueRef>> index = new HashMap<>();
        for (int m = 0; m < traces.size(); m++) {
            Set<String> rootInputs = new HashSet<>();
            for (ValueRef a : argsByTrace.get(m)) {
                if (a.rootSpan) {
                    rootInputs.add(a.value);
                }
            }
            Set<String> classified = new HashSet<>();
            for (ValueRef target : argsByTrace.get(m)) {
                if (frequency.getOrDefault(target.value, 0) > frequencyCap) {
                    continue;
                }
                List<ValueRef> sources = index.get(target.value);
                if (sources != null && !sources.isEmpty()) {
                    file.entries.add(sourced(target, sources));
                } else if (classified.add(target.value)) {
                    unsourced.add(unsourced(target, rootInputs.contains(target.value)));
                }
            }
            // Results of trace m become sources for traces after it; nearest first means last added first.
            for (ValueRef r : resultsByTrace.get(m)) {
                if (frequency.getOrDefault(r.value, 0) > frequencyCap) {
                    continue;
                }
                index.computeIfAbsent(r.value, k -> new ArrayList<>()).add(0, r);
            }
        }
        file.entries.addAll(unsourced);
        return file;
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

    private static LineageEntry unsourced(ValueRef target, boolean fromRootInput) {
        LineageEntry entry = new LineageEntry();
        entry.to = ref(target);
        entry.value = target.value;
        entry.classification = fromRootInput ? LineageEntry.CLASS_USER_INPUT : LineageEntry.CLASS_ENVIRONMENT;
        return entry;
    }

    private static LineageRef ref(ValueRef v) {
        return new LineageRef(v.traceId, v.spanId, v.path);
    }
}
