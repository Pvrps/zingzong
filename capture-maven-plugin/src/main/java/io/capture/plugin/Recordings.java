package io.capture.plugin;

import io.capture.reader.Indexer;
import io.capture.reader.RecordingDir;
import io.capture.trace.RecordingIndex;
import io.capture.trace.RecordingManifest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The recordings under the output directory, newest first, and each one's viewer index.
 * Indexes (traces, sessions, lineage) are computed in-process by trace-reader and cached per
 * directory; a directory is re-read when any file in it is newer or the file count changed,
 * which is how a live recording shows new traces as they land.
 */
public final class Recordings {

    public static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]+");

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

    private final File outDir;
    private final Map<String, Cached> cache = new HashMap<String, Cached>();

    public Recordings(File outDir) {
        this.outDir = outDir;
    }

    public File outDir() {
        return outDir;
    }

    public File dir(String id) {
        return ID.matcher(id).matches() ? new File(outDir, id) : null;
    }

    public boolean exists(String id) {
        File dir = dir(id);
        return dir != null && dir.isDirectory() && isRecording(dir);
    }

    /** All recordings, newest first; {@code activeId} is what the servers currently report. */
    public List<Summary> list(String activeId) {
        List<Summary> result = new ArrayList<Summary>();
        File[] dirs = outDir.listFiles();
        if (dirs == null) {
            return result;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory() || !ID.matcher(dir.getName()).matches() || !isRecording(dir)) {
                continue;
            }
            RecordingManifest manifest = null;
            try {
                manifest = RecordingDir.loadManifest(dir.toPath());
            } catch (IOException ignored) {
                // a manifest being written; show what we have
            }
            int traces;
            try {
                traces = RecordingDir.traceFiles(dir.toPath()).size();
            } catch (IOException e) {
                traces = 0;
            }
            boolean active = dir.getName().equals(activeId);
            List<String> servers = manifest == null || manifest.servers == null ? Collections.<String>emptyList() : manifest.servers;
            result.add(new Summary(dir.getName(),
                    manifest == null ? dir.getName() : manifest.name,
                    manifest == null ? null : manifest.startedAt,
                    manifest == null || active ? null : manifest.endedAt,
                    traces, servers, active));
        }
        Collections.sort(result, new Comparator<Summary>() {
            @Override
            public int compare(Summary a, Summary b) {
                return sortKey(b).compareTo(sortKey(a));
            }
        });
        return result;
    }

    /** startedAt when known, else the timestamp in a rec-yyyyMMdd-HHmmss id in the same shape, else the id. */
    private static String sortKey(Summary s) {
        if (s.startedAt != null) {
            return s.startedAt;
        }
        java.util.regex.Matcher m = Pattern.compile("rec-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2}).*").matcher(s.id);
        if (m.matches()) {
            return m.group(1) + "-" + m.group(2) + "-" + m.group(3) + "T" + m.group(4) + ":" + m.group(5) + ":" + m.group(6);
        }
        return s.id;
    }

    /** The viewer index of one recording, recomputed when the directory changed. */
    public synchronized RecordingIndex index(String id) throws IOException {
        File dir = dir(id);
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        long stamp = stamp(dir);
        Cached cached = cache.get(id);
        if (cached != null && cached.stamp == stamp) {
            return cached.index;
        }
        RecordingIndex index = Indexer.index(RecordingDir.load(dir.toPath()));
        cache.put(id, new Cached(stamp, index));
        return index;
    }

    /** A trace file of a recording, by its file name; null unless it is one. */
    public File traceFile(String id, String file) {
        File dir = dir(id);
        if (dir == null || !RecordingDir.TRACE_FILE.matcher(file).matches()) {
            return null;
        }
        File f = new File(dir, file);
        return f.isFile() ? f : null;
    }

    private static boolean isRecording(File dir) {
        Path p = dir.toPath();
        try {
            return !RecordingDir.manifestFiles(p).isEmpty() || !RecordingDir.traceFiles(p).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** Newest modification time and file count of the directory's files: changes when a trace lands or a manifest is completed. */
    private static long stamp(File dir) {
        File[] files = dir.listFiles();
        long newest = 0;
        int count = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    newest = Math.max(newest, f.lastModified());
                    count++;
                }
            }
        }
        return newest * 31 + count;
    }
}
