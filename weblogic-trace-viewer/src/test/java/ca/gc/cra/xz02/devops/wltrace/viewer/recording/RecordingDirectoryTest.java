package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector;
import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** Proves loading, ordering, lineage and indexing of a recording captured on the server: Create order then Confirm order. */
public class RecordingDirectoryTest {

    private static Path recording() throws Exception {
        URL url = RecordingDirectoryTest.class.getResource("/recordings/create-and-confirm/recording.json");
        assertNotNull(url);
        return Paths.get(url.toURI()).getParent();
    }

    @Test
    public void loadsManifestAndTracesInSeqOrder() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        assertEquals(recording.manifest.id, "rec-20261023-162921");
        assertEquals(recording.manifest.name, "create-and-confirm");
        assertNotNull(recording.manifest.startedAt);
        assertNotNull(recording.manifest.endedAt);
        assertEquals(recording.manifest.agent.version, "1.0.0-SNAPSHOT");
        assertEquals(recording.manifest.traceCount, Long.valueOf(2));
        assertEquals(recording.traces.size(), 2);
        assertEquals(recording.traces.get(0).sequence(), 1);
        assertEquals(SpanNames.span(recording.traces.get(0).root()), "OrderService.createOrder");
        assertEquals(SpanNames.span(recording.traces.get(1).root()), "OrderService.confirmOrder");
    }

    @Test
    public void confirmOrderIdComesFromCreateOrderResult() throws Exception {
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
        assertEquals(link.to.trace, recording.traces.get(1).id());
        assertEquals(link.to.span, "s1");
        assertEquals(link.from.trace, recording.traces.get(0).id());
        assertEquals(link.from.span, "s1");
        assertEquals(link.from.path, "result.id");
        assertNotNull(name);
        assertEquals(name.classification, LineageEntry.CLASS_USER_INPUT);
        assertEquals(name.to.path, "args[0].customer.name");
    }

    @Test
    public void indexSummarisesBothTraces() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        RecordingIndex index = new RecordingIndexer(new LineageDetector()).index(recording);
        assertEquals(index.traces.size(), 2);
        assertEquals(index.traces.get(0).rootLabel, "OrderService.createOrder");
        assertEquals(index.traces.get(0).spanCount, 9);
        assertTrue(index.lineage.size() >= 2);
    }
}
