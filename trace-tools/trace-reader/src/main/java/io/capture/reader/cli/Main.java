package io.capture.reader.cli;

import io.capture.reader.Indexer;
import io.capture.reader.Labels;
import io.capture.reader.LoadedTrace;
import io.capture.reader.RecordingDir;
import io.capture.reader.TraceValidator;
import io.capture.reader.TreePrinter;
import io.capture.reader.lineage.LineageDetector;
import io.capture.trace.LineageEntry;
import io.capture.trace.LineageFile;
import io.capture.trace.LineageRef;
import io.capture.trace.RecordingIndex;
import io.capture.trace.Trace;
import io.capture.trace.TraceJson;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * <pre>
 *   trace-reader validate &lt;dir&gt;   schema-check every trace file
 *   trace-reader tree &lt;file&gt;      print one trace as an indented span tree
 *   trace-reader lineage &lt;dir&gt;    detect lineage, print it, write lineage.json
 *   trace-reader index &lt;dir&gt;      write recording-index.json (traces, sessions, lineage)
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String command = args[0];
        Path target = Paths.get(args[1]);
        int exit;
        switch (command) {
            case "validate":
                exit = validate(target);
                break;
            case "tree":
                exit = tree(target);
                break;
            case "lineage":
                exit = lineage(target);
                break;
            case "index":
                exit = index(target);
                break;
            default:
                usage();
                exit = 2;
        }
        System.exit(exit);
    }

    private static void usage() {
        System.err.println("usage: trace-reader validate <dir> | tree <file> | lineage <dir> | index <dir>");
    }

    static int validate(Path dir) throws Exception {
        TraceValidator validator = new TraceValidator();
        List<Path> files = RecordingDir.traceFiles(dir);
        Map<String, List<String>> problems = validator.validateAll(dir);
        for (Map.Entry<String, List<String>> e : problems.entrySet()) {
            System.out.println(e.getKey() + ":");
            for (String p : e.getValue()) {
                System.out.println("  " + p);
            }
        }
        RecordingDir recording = RecordingDir.load(dir);
        System.out.println((problems.isEmpty() ? "OK: " : "FAILED: ") + files.size() + " trace files, "
                + problems.size() + " with problems; recording " + recording.manifest.id
                + " (" + recording.manifest.name + ")"
                + (recording.manifest.endedAt == null ? ", still open or manifest missing" : ""));
        return problems.isEmpty() ? 0 : 1;
    }

    static int tree(Path file) throws Exception {
        Trace trace;
        try (InputStream in = Files.newInputStream(file)) {
            trace = TraceJson.read(in);
        }
        System.out.print(TreePrinter.print(trace));
        return 0;
    }

    static int lineage(Path dir) throws Exception {
        RecordingDir recording = RecordingDir.load(dir);
        LineageFile lineage = new LineageDetector().detect(recording.manifest.id, recording.traces);
        print(recording, lineage);
        write(lineage, dir.resolve(RecordingDir.LINEAGE_FILE));
        System.out.println("wrote " + dir.resolve(RecordingDir.LINEAGE_FILE));
        return 0;
    }

    static int index(Path dir) throws Exception {
        RecordingDir recording = RecordingDir.load(dir);
        LineageFile lineage = new LineageDetector().detect(recording.manifest.id, recording.traces);
        RecordingIndex index = Indexer.index(recording, lineage);
        write(lineage, dir.resolve(RecordingDir.LINEAGE_FILE));
        write(index, dir.resolve(RecordingDir.INDEX_FILE));
        System.out.println("recording " + recording.manifest.id + ": " + index.traces.size() + " traces, "
                + index.sessions.size() + " sessions, " + index.lineage.size() + " lineage entries");
        System.out.println("wrote " + dir.resolve(RecordingDir.INDEX_FILE));
        return 0;
    }

    private static void print(RecordingDir recording, LineageFile lineage) {
        int sourced = 0;
        for (LineageEntry entry : lineage.entries) {
            if (entry.from != null) {
                sourced++;
                System.out.println(describe(recording, entry.to) + " \u2190 " + describe(recording, entry.from)
                        + "   [" + entry.value + "]"
                        + (entry.alternatives == null ? "" : "  alternatives=" + entry.alternatives.size())
                        + (Boolean.TRUE.equals(entry.alsoProducedHere) ? "  (also in this trace's own results)" : ""));
                if (entry.alternatives != null) {
                    for (LineageRef alt : entry.alternatives) {
                        System.out.println("    also from " + describe(recording, alt));
                    }
                }
            }
        }
        System.out.println(sourced + " sourced value(s)");
        for (LineageEntry entry : lineage.entries) {
            if (entry.from == null) {
                System.out.println(entry.classification + ": " + describe(recording, entry.to) + "   [" + entry.value + "]");
            }
        }
    }

    private static String describe(RecordingDir recording, LineageRef ref) {
        LoadedTrace trace = recording.trace(ref.trace);
        String label = trace == null ? "?" : Labels.span(trace.span(ref.span));
        return "trace " + (trace == null ? ref.trace : trace.seq()) + " / " + label + " / " + ref.path;
    }

    private static void write(Object document, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            TraceJson.write(document, out);
        }
    }
}
