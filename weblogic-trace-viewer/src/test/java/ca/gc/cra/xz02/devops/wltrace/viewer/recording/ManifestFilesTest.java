package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves which file names count as a server manifest. */
public class ManifestFilesTest {

    @Test
    public void manifestNamesPerServerButNotTheIndex() {
        assertTrue(RecordingDirectory.MANIFEST_FILE.matcher("recording.json").matches());
        assertTrue(RecordingDirectory.MANIFEST_FILE.matcher("recording-AdminServer.json").matches());
        assertTrue(RecordingDirectory.MANIFEST_FILE.matcher("recording-ms_1.json").matches());
        assertFalse(RecordingDirectory.MANIFEST_FILE.matcher("recording-index.json").matches());
        assertFalse(RecordingDirectory.MANIFEST_FILE.matcher("lineage.json").matches());
        assertFalse(RecordingDirectory.MANIFEST_FILE.matcher("000001-t-abc.json").matches());
    }
}
