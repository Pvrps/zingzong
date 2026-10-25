package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector;
import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves loading, ordering, lineage and indexing of a recording captured on the server: Create order then Confirm order. */
class RecordingDirectoryTest {

    private static Path recording() throws Exception {
        URL url = RecordingDirectoryTest.class.getResource("/recordings/create-and-confirm/recording.json");
        assertNotNull(url);
        return Paths.get(url.toURI()).getParent();
    }

    @Test
    void loadsManifestAndTracesInSeqOrder() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        assertEquals("rec-20261023-162921", recording.manifest.id);
        assertEquals("create-and-confirm", recording.manifest.name);
        assertNotNull(recording.manifest.startedAt);
        assertNotNull(recording.manifest.endedAt);
        assertEquals("1.0.0-SNAPSHOT", recording.manifest.agent.version);
        assertEquals(Long.valueOf(2), recording.manifest.traceCount);
        assertEquals(2, recording.traces.size());
        assertEquals(1, recording.traces.get(0).sequence());
        assertEquals("OrderService.createOrder", SpanLabels.span(recording.traces.get(0).root()));
        assertEquals("OrderService.confirmOrder", SpanLabels.span(recording.traces.get(1).root()));
    }

    @Test
    void confirmOrderIdComesFromCreateOrderResult() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        LineageFile lineage = new LineageDetector().detect(recording.manifest.id, recording.traces);

        LineageEntry link = null;
        LineageEntry name = null;
        for (LineageEntry entry : lineage.entries) {
            if (entry.from != null && "args[0].orderId".equals(entry.to.path)) {
                link = entry;
            }
            if ("Ada Lovelace".equals(entry.value)) {
                name = entry;
            }
        }
        assertNotNull(link);
        assertEquals(recording.traces.get(1).id(), link.to.trace);
        assertEquals("s1", link.to.span);
        assertEquals(recording.traces.get(0).id(), link.from.trace);
        assertEquals("s1", link.from.span);
        assertEquals("result.id", link.from.path);
        assertNotNull(name);
        assertEquals(LineageEntry.CLASS_USER_INPUT, name.classification);
        assertEquals("args[0].customer.name", name.to.path);
    }

    @Test
    void indexSummarisesBothTraces() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        RecordingIndex index = new RecordingIndexer(new LineageDetector()).index(recording);
        assertEquals(2, index.traces.size());
        assertEquals("OrderService.createOrder", index.traces.get(0).rootLabel);
        assertEquals(9, index.traces.get(0).spanCount);
        assertTrue(index.lineage.size() >= 2);
    }
}
