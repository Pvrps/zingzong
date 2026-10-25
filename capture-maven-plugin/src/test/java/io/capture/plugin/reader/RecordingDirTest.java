package io.capture.plugin.reader;

import io.capture.plugin.reader.lineage.LineageDetector;
import io.capture.trace.LineageEntry;
import io.capture.trace.LineageFile;
import io.capture.trace.RecordingIndex;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves loading, ordering, lineage and indexing of a recording captured on the server: Create order then Confirm order. */
class RecordingDirTest {

    private static Path recording() throws Exception {
        URL url = RecordingDirTest.class.getResource("/recordings/create-and-confirm/recording.json");
        assertNotNull(url);
        return Paths.get(url.toURI()).getParent();
    }

    @Test
    void loadsManifestAndTracesInSeqOrder() throws Exception {
        RecordingDir dir = RecordingDir.load(recording());
        assertEquals("rec-20261023-162921", dir.manifest.id);
        assertEquals("create-and-confirm", dir.manifest.name);
        assertNotNull(dir.manifest.startedAt);
        assertNotNull(dir.manifest.endedAt);
        assertEquals("0.1.0-SNAPSHOT", dir.manifest.agent.version);
        assertEquals(Long.valueOf(2), dir.manifest.traceCount);
        assertEquals(2, dir.traces.size());
        assertEquals(1, dir.traces.get(0).seq());
        assertEquals("OrderService.createOrder", Labels.span(dir.traces.get(0).root()));
        assertEquals("OrderService.confirmOrder", Labels.span(dir.traces.get(1).root()));
    }

    @Test
    void confirmOrderIdComesFromCreateOrderResult() throws Exception {
        RecordingDir dir = RecordingDir.load(recording());
        LineageFile lineage = new LineageDetector().detect(dir.manifest.id, dir.traces);

        LineageEntry link = null;
        LineageEntry name = null;
        for (LineageEntry e : lineage.entries) {
            if (e.from != null && "args[0].orderId".equals(e.to.path)) {
                link = e;
            }
            if ("Ada Lovelace".equals(e.value)) {
                name = e;
            }
        }
        assertNotNull(link);
        assertEquals(dir.traces.get(1).id(), link.to.trace);
        assertEquals("s1", link.to.span);
        assertEquals(dir.traces.get(0).id(), link.from.trace);
        assertEquals("s1", link.from.span);
        assertEquals("result.id", link.from.path);
        assertNotNull(name);
        assertEquals(LineageEntry.CLASS_USER_INPUT, name.classification);
        assertEquals("args[0].customer.name", name.to.path);
    }

    @Test
    void indexSummarisesBothTraces() throws Exception {
        RecordingDir dir = RecordingDir.load(recording());
        RecordingIndex index = Indexer.index(dir);
        assertEquals(2, index.traces.size());
        assertEquals("OrderService.createOrder", index.traces.get(0).rootLabel);
        assertEquals(9, index.traces.get(0).spanCount);
        assertTrue(index.lineage.size() >= 2);
    }
}
