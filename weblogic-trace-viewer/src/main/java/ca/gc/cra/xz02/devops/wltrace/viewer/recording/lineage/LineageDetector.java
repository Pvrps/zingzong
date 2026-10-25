package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.LineageRef;
import ca.gc.cra.xz02.devops.wltrace.viewer.recording.LoadedTrace;

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
import java.util.stream.Collectors;

/**
 * Finds values in later inputs that came from earlier outputs. Annotates, never removes: every
 * distinctive input value ends up either sourced or classified.
 *
 * <p>Traces are walked in order. Every distinctive scalar in a span's result and in a root
 * request's response body goes into a source index under its normalised form; every distinctive
 * scalar in a later trace's arguments, request body, URL path and query is looked up there. A hit
 * is an entry {@code to <- from} with the nearest earlier source first and the others as alternatives.
 *
 * <p>Three rules shape the index. A value produced as a result by more than {@code frequencyCap}
 * distinct traces is too common to be a source (an echoed constant, a status) and is not indexed.
 * A value first seen in a root span's input is user-originated and is never linked when it appears
 * again: it was typed again, and the results that echoed it in between are not its source. An
 * input without a source is classified {@code environment} when it is an instant inside the
 * trace's own time window or appears only in nested spans, else {@code userInput}; an entry whose
 * value the same trace also produces is flagged {@code alsoProducedHere}.
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
        List<List<ValueLocation>> outputsByTrace = traces.stream().map(ValueFlattener::outputs).collect(Collectors.toList());
        List<List<ValueLocation>> inputsByTrace = traces.stream().map(ValueFlattener::inputs).collect(Collectors.toList());
        Map<String, Set<Integer>> producingTraces = new HashMap<>();
        for (int position = 0; position < traces.size(); position++) {
            for (ValueLocation output : outputsByTrace.get(position)) {
                producingTraces.computeIfAbsent(output.value, value -> new HashSet<>()).add(traces.get(position).index);
            }
        }

        LineageFile file = new LineageFile();
        file.recordingId = recordingId;
        file.generatedAt = Instant.now().toString();
        List<LineageEntry> unsourced = new ArrayList<>();

        Map<String, List<ValueLocation>> sourceIndex = new HashMap<>();
        Set<String> userOriginated = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int position = 0; position < traces.size(); position++) {
            LoadedTrace trace = traces.get(position);
            Set<String> producedHere = outputsByTrace.get(position).stream().map(output -> output.value).collect(Collectors.toSet());
            Set<String> rootInputs = new HashSet<>();
            for (ValueLocation input : inputsByTrace.get(position)) {
                if (input.rootSpan) {
                    rootInputs.add(input.value);
                    if (seen.add(input.value)) {
                        userOriginated.add(input.value);
                    }
                }
            }
            Set<String> classified = new HashSet<>();
            for (ValueLocation target : inputsByTrace.get(position)) {
                seen.add(target.value);
                List<ValueLocation> sources = userOriginated.contains(target.value) ? null : sourceIndex.get(target.value);
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
            // The outputs of this trace become sources for later traces; nearest first means last added first.
            for (ValueLocation output : outputsByTrace.get(position)) {
                seen.add(output.value);
                if (producingTraces.getOrDefault(output.value, Collections.emptySet()).size() > frequencyCap) {
                    continue;
                }
                sourceIndex.computeIfAbsent(output.value, value -> new ArrayList<>()).add(0, output);
            }
        }
        file.entries.addAll(unsourced);
        return file;
    }

    private static String classify(ValueLocation target, Set<String> rootInputs, LoadedTrace trace) {
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
        } catch (DateTimeParseException notAnInstant) {
            return false;
        }
    }

    private static LineageEntry sourced(ValueLocation target, List<ValueLocation> sources) {
        LineageEntry entry = new LineageEntry();
        entry.to = reference(target);
        entry.from = reference(sources.get(0));
        entry.value = target.value;
        if (sources.size() > 1) {
            entry.alternatives = sources.subList(1, sources.size()).stream().map(LineageDetector::reference).collect(Collectors.toList());
        }
        return entry;
    }

    private static LineageEntry unsourced(ValueLocation target, String classification) {
        LineageEntry entry = new LineageEntry();
        entry.to = reference(target);
        entry.value = target.value;
        entry.classification = classification;
        return entry;
    }

    private static LineageRef reference(ValueLocation location) {
        return new LineageRef(location.traceId, location.spanId, location.path);
    }
}
