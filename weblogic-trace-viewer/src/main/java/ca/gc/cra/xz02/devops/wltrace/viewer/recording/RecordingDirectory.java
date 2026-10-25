package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest;
import ca.gc.cra.xz02.devops.wltrace.model.Trace;
import ca.gc.cra.xz02.devops.wltrace.model.TraceJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A recording directory as the agent writes it: one manifest per server that took part
 * ({@code recording.json} or {@code recording-<server>.json}) plus {@code NNNNNN-t-*.json} trace
 * files. The servers' manifests are merged into one view; traces are ordered by start time, then
 * by each server's own sequence number.
 */
public final class RecordingDirectory {

    public static final Pattern TRACE_FILE = Pattern.compile("\\d{6}-t-[0-9a-f]+\\.json");
    /** {@code recording.json} or {@code recording-<server>.json}. */
    public static final Pattern MANIFEST_FILE = Pattern.compile("recording(-(?!index\\.json$)[A-Za-z0-9._-]+)?\\.json");

    public final Path directory;
    public final RecordingManifest manifest;
    public final List<LoadedTrace> traces;

    private RecordingDirectory(Path directory, RecordingManifest manifest, List<LoadedTrace> traces) {
        this.directory = directory;
        this.manifest = manifest;
        this.traces = traces;
    }

    /** Reads every trace file and the manifests; a directory without a manifest gets one made from its name and first trace. */
    public static RecordingDirectory load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("not a directory: " + directory);
        }
        List<Path> files = traceFiles(directory);
        List<LoadedTrace> unordered = new ArrayList<>();
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                unordered.add(new LoadedTrace(file, TraceJson.read(in), 0));
            } catch (IOException failure) {
                throw new IOException(file.getFileName() + ": " + failure.getMessage(), failure);
            }
        }
        unordered.sort(Comparator.comparing((LoadedTrace loaded) -> startedAt(loaded.trace)).thenComparingLong(LoadedTrace::sequence));
        List<LoadedTrace> traces = new ArrayList<>();
        for (int index = 0; index < unordered.size(); index++) {
            traces.add(new LoadedTrace(unordered.get(index).file, unordered.get(index).trace, index));
        }
        RecordingManifest manifest = loadManifest(directory).orElseGet(() -> manifestFromTraces(directory, traces));
        return new RecordingDirectory(directory, manifest, Collections.unmodifiableList(traces));
    }

    private static String startedAt(Trace trace) {
        return trace.trace == null || trace.trace.startedAt == null ? "" : trace.trace.startedAt;
    }

    private static RecordingManifest manifestFromTraces(Path directory, List<LoadedTrace> traces) {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = directory.getFileName().toString();
        manifest.name = traces.isEmpty() || traces.get(0).trace.recording == null ? manifest.id : traces.get(0).trace.recording.name;
        return manifest;
    }

    /**
     * The servers' manifests merged: earliest start, latest end (absent while any server is still
     * recording), summed counts, every server name; empty when the directory has no manifest.
     */
    public static Optional<RecordingManifest> loadManifest(Path directory) throws IOException {
        List<Path> files = manifestFiles(directory);
        if (files.isEmpty()) {
            return Optional.empty();
        }
        RecordingManifest merged = null;
        boolean open = false;
        for (Path file : files) {
            RecordingManifest manifest;
            try (InputStream in = Files.newInputStream(file)) {
                manifest = TraceJson.read(in, RecordingManifest.class);
            }
            if (merged == null) {
                merged = manifest;
                merged.servers = new ArrayList<>();
                merged.traceCount = manifest.traceCount == null ? Long.valueOf(0) : manifest.traceCount;
                merged.droppedTraces = manifest.droppedTraces == null ? Long.valueOf(0) : manifest.droppedTraces;
            } else {
                if (manifest.startedAt != null && (merged.startedAt == null || manifest.startedAt.compareTo(merged.startedAt) < 0)) {
                    merged.startedAt = manifest.startedAt;
                }
                if (manifest.endedAt != null && (merged.endedAt == null || manifest.endedAt.compareTo(merged.endedAt) > 0)) {
                    merged.endedAt = manifest.endedAt;
                }
                merged.traceCount += manifest.traceCount == null ? 0 : manifest.traceCount;
                merged.droppedTraces += manifest.droppedTraces == null ? 0 : manifest.droppedTraces;
            }
            open |= manifest.endedAt == null;
            if (manifest.serverName != null && !merged.servers.contains(manifest.serverName)) {
                merged.servers.add(manifest.serverName);
            }
        }
        if (open) {
            merged.endedAt = null;
        }
        if (merged.servers.size() > 1) {
            merged.serverName = null;
        }
        return Optional.of(merged);
    }

    public static List<Path> traceFiles(Path directory) throws IOException {
        return matching(directory, TRACE_FILE);
    }

    public static List<Path> manifestFiles(Path directory) throws IOException {
        return matching(directory, MANIFEST_FILE);
    }

    private static List<Path> matching(Path directory, Pattern pattern) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry) && pattern.matcher(entry.getFileName().toString()).matches()) {
                    files.add(entry);
                }
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    public Optional<LoadedTrace> trace(String id) {
        return traces.stream().filter(loaded -> loaded.id().equals(id)).findFirst();
    }
}
