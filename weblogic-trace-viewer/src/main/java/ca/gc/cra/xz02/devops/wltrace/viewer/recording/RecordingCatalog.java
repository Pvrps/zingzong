package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest;
import ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recordings under the output directory, newest first, and each one's index. Indexes are
 * cached per directory and recomputed when any file in it is newer or the file count changed,
 * which is how a live recording shows new traces as they land.
 */
public final class RecordingCatalog {

    public static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ID_TIMESTAMP = Pattern.compile("rec-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2}).*");

    /** One row of the recordings list. */
    public static final class Summary {
        public final String id;
        public final String name;
        public final String startedAt;
        public final String endedAt;
        public final int traceCount;
        public final List<String> servers;
        public final boolean active;

        Summary(String id, String name, String startedAt, String endedAt, int traceCount, List<String> servers, boolean active) {
            this.id = id;
            this.name = name;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.traceCount = traceCount;
            this.servers = servers;
            this.active = active;
        }

        /** {@code startedAt} when known, else the timestamp of a {@code rec-yyyyMMdd-HHmmss} id in the same shape, else the id. */
        String sortKey() {
            if (startedAt != null) {
                return startedAt;
            }
            Matcher timestamp = ID_TIMESTAMP.matcher(id);
            if (timestamp.matches()) {
                return timestamp.group(1) + "-" + timestamp.group(2) + "-" + timestamp.group(3)
                        + "T" + timestamp.group(4) + ":" + timestamp.group(5) + ":" + timestamp.group(6);
            }
            return id;
        }
    }

    private static final class CachedIndex {
        final long stamp;
        final RecordingIndex index;

        CachedIndex(long stamp, RecordingIndex index) {
            this.stamp = stamp;
            this.index = index;
        }
    }

    private final Path outputDirectory;
    private final RecordingIndexer indexer;
    private final Map<String, CachedIndex> cache = new HashMap<>();

    public RecordingCatalog(Path outputDirectory) {
        this(outputDirectory, new RecordingIndexer(new LineageDetector()));
    }

    public RecordingCatalog(Path outputDirectory, RecordingIndexer indexer) {
        this.outputDirectory = outputDirectory;
        this.indexer = indexer;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    /** The directory of a recording id; empty when the id is not a plain name. */
    public Optional<Path> directory(String id) {
        return ID.matcher(id).matches() ? Optional.of(outputDirectory.resolve(id)) : Optional.empty();
    }

    public boolean exists(String id) {
        return directory(id).filter(path -> Files.isDirectory(path) && isRecording(path)).isPresent();
    }

    /** All recordings, newest first; {@code activeId} is what the servers report as recording. */
    public List<Summary> list(String activeId) {
        List<Summary> result = new ArrayList<>();
        if (!Files.isDirectory(outputDirectory)) {
            return result;
        }
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(outputDirectory)) {
            for (Path directory : directories) {
                String id = directory.getFileName().toString();
                if (!Files.isDirectory(directory) || !ID.matcher(id).matches() || !isRecording(directory)) {
                    continue;
                }
                result.add(summarize(directory, id, id.equals(activeId)));
            }
        } catch (IOException failure) {
            return result;
        }
        result.sort(Comparator.comparing(Summary::sortKey).reversed());
        return result;
    }

    private static Summary summarize(Path directory, String id, boolean active) {
        RecordingManifest manifest = null;
        try {
            manifest = RecordingDirectory.loadManifest(directory).orElse(null);
        } catch (IOException ignored) {
            // a manifest being written; the row shows what is known
        }
        int traces;
        try {
            traces = RecordingDirectory.traceFiles(directory).size();
        } catch (IOException failure) {
            traces = 0;
        }
        List<String> servers = manifest == null || manifest.servers == null ? Collections.emptyList() : manifest.servers;
        return new Summary(id,
                manifest == null ? id : manifest.name,
                manifest == null ? null : manifest.startedAt,
                manifest == null || active ? null : manifest.endedAt,
                traces, servers, active);
    }

    /** The index of one recording, recomputed when the directory changed; empty when the id is not a recording. */
    public synchronized Optional<RecordingIndex> index(String id) throws IOException {
        Optional<Path> directory = directory(id).filter(Files::isDirectory);
        if (!directory.isPresent()) {
            return Optional.empty();
        }
        long stamp = stamp(directory.get());
        CachedIndex cached = cache.get(id);
        if (cached != null && cached.stamp == stamp) {
            return Optional.of(cached.index);
        }
        RecordingIndex index = indexer.index(RecordingDirectory.load(directory.get()));
        cache.put(id, new CachedIndex(stamp, index));
        return Optional.of(index);
    }

    /** A trace file of a recording by its file name; empty unless the name is a trace file that exists. */
    public Optional<Path> traceFile(String id, String fileName) {
        if (!RecordingDirectory.TRACE_FILE.matcher(fileName).matches()) {
            return Optional.empty();
        }
        return directory(id).map(path -> path.resolve(fileName)).filter(Files::isRegularFile);
    }

    /**
     * Removes a recording: its trace files, manifests and directory. Refuses the active one, which
     * the servers are still writing into. Returns how many files were deleted.
     *
     * @throws IllegalStateException when the recording is the active one
     */
    public synchronized int delete(String id, String activeId) throws IOException {
        Path directory = directory(id).filter(path -> Files.isDirectory(path) && isRecording(path))
                .orElseThrow(() -> new IOException("no recording " + id));
        if (id.equals(activeId)) {
            throw new IllegalStateException("recording " + id + " is active; stop it first");
        }
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    Files.delete(file);
                    deleted++;
                }
            }
        }
        try {
            Files.delete(directory);
        } catch (IOException failure) {
            throw new IOException("could not delete " + directory + " (something besides recording files is in it)", failure);
        }
        cache.remove(id);
        return deleted;
    }

    private static boolean isRecording(Path directory) {
        try {
            return !RecordingDirectory.manifestFiles(directory).isEmpty() || !RecordingDirectory.traceFiles(directory).isEmpty();
        } catch (IOException failure) {
            return false;
        }
    }

    /** Newest modification time and file count of the directory's files: changes when a trace lands or a manifest completes. */
    private static long stamp(Path directory) {
        long newest = 0;
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) {
                    newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                    count++;
                }
            }
        } catch (IOException failure) {
            return -1;
        }
        return newest * 31 + count;
    }
}
