package ca.gc.cra.xz02.devops.wltrace.plugin;

import ca.gc.cra.xz02.devops.wltrace.plugin.reader.Indexer;
import ca.gc.cra.xz02.devops.wltrace.plugin.reader.RecordingDir;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recordings under the output directory, newest first, and each one's viewer index. Indexes
 * are cached per directory and recomputed when any file in it is newer or the file count changed,
 * which is how a live recording shows new traces as they land.
 */
public final class Recordings {

    public static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ID_TIMESTAMP = Pattern.compile("rec-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2}).*");

    /** One row of the home view. */
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
    }

    private static final class Cached {
        final long stamp;
        final RecordingIndex index;

        Cached(long stamp, RecordingIndex index) {
            this.stamp = stamp;
            this.index = index;
        }
    }

    private final Path outDir;
    private final Map<String, Cached> cache = new HashMap<String, Cached>();

    public Recordings(Path outDir) {
        this.outDir = outDir;
    }

    public Path outDir() {
        return outDir;
    }

    public Path dir(String id) {
        return ID.matcher(id).matches() ? outDir.resolve(id) : null;
    }

    public boolean exists(String id) {
        Path dir = dir(id);
        return dir != null && Files.isDirectory(dir) && isRecording(dir);
    }

    /** All recordings, newest first; {@code activeId} is what the servers currently report. */
    public List<Summary> list(String activeId) {
        List<Summary> result = new ArrayList<Summary>();
        if (!Files.isDirectory(outDir)) {
            return result;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(outDir)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || !ID.matcher(id).matches() || !isRecording(dir)) {
                    continue;
                }
                RecordingManifest manifest = null;
                try {
                    manifest = RecordingDir.loadManifest(dir);
                } catch (IOException ignored) {
                    // a manifest being written; show what we have
                }
                int traces;
                try {
                    traces = RecordingDir.traceFiles(dir).size();
                } catch (IOException e) {
                    traces = 0;
                }
                boolean active = id.equals(activeId);
                List<String> servers = manifest == null || manifest.servers == null ? Collections.<String>emptyList() : manifest.servers;
                result.add(new Summary(id,
                        manifest == null ? id : manifest.name,
                        manifest == null ? null : manifest.startedAt,
                        manifest == null || active ? null : manifest.endedAt,
                        traces, servers, active));
            }
        } catch (IOException e) {
            return result;
        }
        Collections.sort(result, new Comparator<Summary>() {
            @Override
            public int compare(Summary a, Summary b) {
                return sortKey(b).compareTo(sortKey(a));
            }
        });
        return result;
    }

    /** startedAt when known, else the timestamp of a rec-yyyyMMdd-HHmmss id in the same shape, else the id. */
    private static String sortKey(Summary s) {
        if (s.startedAt != null) {
            return s.startedAt;
        }
        Matcher m = ID_TIMESTAMP.matcher(s.id);
        if (m.matches()) {
            return m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "T" + m.group(4) + ":" + m.group(5) + ":" + m.group(6);
        }
        return s.id;
    }

    /** The viewer index of one recording, recomputed when the directory changed. */
    public synchronized RecordingIndex index(String id) throws IOException {
        Path dir = dir(id);
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        long stamp = stamp(dir);
        Cached cached = cache.get(id);
        if (cached != null && cached.stamp == stamp) {
            return cached.index;
        }
        RecordingIndex index = Indexer.index(RecordingDir.load(dir));
        cache.put(id, new Cached(stamp, index));
        return index;
    }

    /** A trace file of a recording, by its file name; null unless it is one. */
    public Path traceFile(String id, String file) {
        Path dir = dir(id);
        if (dir == null || !RecordingDir.TRACE_FILE.matcher(file).matches()) {
            return null;
        }
        Path f = dir.resolve(file);
        return Files.isRegularFile(f) ? f : null;
    }

    /**
     * Removes a recording: its trace files, manifests and directory. Refuses the active one, which
     * the servers are still writing into. Returns how many files were deleted.
     */
    public synchronized int delete(String id, String activeId) throws IOException {
        Path dir = dir(id);
        if (dir == null || !Files.isDirectory(dir) || !isRecording(dir)) {
            throw new IOException("no recording " + id);
        }
        if (id.equals(activeId)) {
            throw new IllegalStateException("recording " + id + " is active; stop it first");
        }
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (Files.isRegularFile(f)) {
                    Files.delete(f);
                    deleted++;
                }
            }
        }
        try {
            Files.delete(dir);
        } catch (IOException e) {
            throw new IOException("could not delete " + dir + " (something besides recording files is in it)", e);
        }
        cache.remove(id);
        return deleted;
    }

    private static boolean isRecording(Path dir) {
        try {
            return !RecordingDir.manifestFiles(dir).isEmpty() || !RecordingDir.traceFiles(dir).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** Newest modification time and file count of the directory's files: changes when a trace lands or a manifest is completed. */
    private static long stamp(Path dir) {
        long newest = 0;
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                if (Files.isRegularFile(f)) {
                    newest = Math.max(newest, Files.getLastModifiedTime(f).toMillis());
                    count++;
                }
            }
        } catch (IOException e) {
            return -1;
        }
        return newest * 31 + count;
    }
}
