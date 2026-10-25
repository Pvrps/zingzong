package io.capture.plugin.reader;

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

/**
 * A recording directory: one manifest per server that took part ({@code recording.json} or
 * {@code recording-<server>.json}) plus {@code NNNNNN-t-*.json} trace files. The servers'
 * manifests are merged into one view; traces are ordered by start time, then by each
 * server's own sequence number.
 */
public final class RecordingDir {

    public static final Pattern TRACE_FILE = Pattern.compile("\\d{6}-t-[0-9a-f]+\\.json");
    /** recording.json or recording-<server>.json. */
    public static final Pattern MANIFEST_FILE = Pattern.compile("recording(-(?!index\\.json$)[A-Za-z0-9._-]+)?\\.json");

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
        RecordingManifest manifest = loadManifest(dir);
        List<Path> files = traceFiles(dir);
        final List<Trace> parsed = new ArrayList<>();
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
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                Trace ta = parsed.get(a);
                Trace tb = parsed.get(b);
                String sa = ta.trace == null || ta.trace.startedAt == null ? "" : ta.trace.startedAt;
                String sb = tb.trace == null || tb.trace.startedAt == null ? "" : tb.trace.startedAt;
                int byStart = sa.compareTo(sb);
                return byStart != 0 ? byStart : Long.compare(seqOf(ta), seqOf(tb));
            }
        });
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

    private static long seqOf(Trace t) {
        return t.recording == null ? 0 : t.recording.seq;
    }

    /** The servers' manifests merged: earliest start, latest end (null while any server is still recording), summed counts. */
    public static RecordingManifest loadManifest(Path dir) throws IOException {
        List<Path> files = manifestFiles(dir);
        if (files.isEmpty()) {
            return null;
        }
        RecordingManifest merged = null;
        boolean open = false;
        for (Path file : files) {
            RecordingManifest m;
            try (InputStream in = Files.newInputStream(file)) {
                m = TraceJson.read(in, RecordingManifest.class);
            }
            if (merged == null) {
                merged = m;
                merged.servers = new ArrayList<>();
                merged.traceCount = m.traceCount == null ? Long.valueOf(0) : m.traceCount;
                merged.droppedTraces = m.droppedTraces == null ? Long.valueOf(0) : m.droppedTraces;
            } else {
                if (m.startedAt != null && (merged.startedAt == null || m.startedAt.compareTo(merged.startedAt) < 0)) {
                    merged.startedAt = m.startedAt;
                }
                if (m.endedAt != null && (merged.endedAt == null || m.endedAt.compareTo(merged.endedAt) > 0)) {
                    merged.endedAt = m.endedAt;
                }
                merged.traceCount += m.traceCount == null ? 0 : m.traceCount;
                merged.droppedTraces += m.droppedTraces == null ? 0 : m.droppedTraces;
            }
            open |= m.endedAt == null;
            if (m.serverName != null && !merged.servers.contains(m.serverName)) {
                merged.servers.add(m.serverName);
            }
        }
        if (open) {
            merged.endedAt = null;
        }
        if (merged.servers.size() > 1) {
            merged.serverName = null;
        }
        return merged;
    }

    public static List<Path> traceFiles(Path dir) throws IOException {
        return matching(dir, TRACE_FILE);
    }

    public static List<Path> manifestFiles(Path dir) throws IOException {
        return matching(dir, MANIFEST_FILE);
    }

    private static List<Path> matching(Path dir, Pattern pattern) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && pattern.matcher(p.getFileName().toString()).matches()) {
                    files.add(p);
                }
            }
        }
        Collections.sort(files, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
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
