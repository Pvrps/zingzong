package io.capture.reader;

import io.capture.trace.RecordingManifest;
import io.capture.trace.Trace;
import io.capture.trace.TraceJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** A recording directory: {@code recording.json} plus {@code NNNNNN-t-*.json} trace files, ordered by seq. */
public final class RecordingDir {

    public static final Pattern TRACE_FILE = Pattern.compile("\\d{6}-t-[0-9a-f]+\\.json");
    public static final String MANIFEST_FILE = "recording.json";
    public static final String LINEAGE_FILE = "lineage.json";
    public static final String INDEX_FILE = "recording-index.json";

    public final Path dir;
    public final RecordingManifest manifest;
    public final List<LoadedTrace> traces;

    private RecordingDir(Path dir, RecordingManifest manifest, List<LoadedTrace> traces) {
        this.dir = dir;
        this.manifest = manifest;
        this.traces = traces;
    }

    public static RecordingDir load(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("not a directory: " + dir);
        }
        RecordingManifest manifest = null;
        Path manifestFile = dir.resolve(MANIFEST_FILE);
        if (Files.isRegularFile(manifestFile)) {
            try (InputStream in = Files.newInputStream(manifestFile)) {
                manifest = TraceJson.read(in, RecordingManifest.class);
            }
        }
        List<Path> files = traceFiles(dir);
        List<Trace> parsed = new ArrayList<>();
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                parsed.add(TraceJson.read(in));
            } catch (IOException e) {
                throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            order.add(i);
        }
        List<Trace> finalParsed = parsed;
        order.sort(Comparator.comparingLong(i -> finalParsed.get(i).recording == null ? 0 : finalParsed.get(i).recording.seq));
        List<LoadedTrace> traces = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            int original = order.get(i);
            traces.add(new LoadedTrace(files.get(original), parsed.get(original), i));
        }
        if (manifest == null) {
            manifest = new RecordingManifest();
            manifest.id = dir.getFileName().toString();
            manifest.name = traces.isEmpty() || traces.get(0).trace.recording == null ? manifest.id : traces.get(0).trace.recording.name;
        }
        return new RecordingDir(dir, manifest, Collections.unmodifiableList(traces));
    }

    public static List<Path> traceFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && TRACE_FILE.matcher(p.getFileName().toString()).matches()) {
                    files.add(p);
                }
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    public LoadedTrace trace(String id) {
        for (LoadedTrace t : traces) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }
}
