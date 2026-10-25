package io.capture.plugin.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves which file names count as a server manifest. */
class ManifestFilesTest {

    @Test
    void manifestNamesPerServerButNotTheIndex() {
        assertTrue(RecordingDir.MANIFEST_FILE.matcher("recording.json").matches());
        assertTrue(RecordingDir.MANIFEST_FILE.matcher("recording-AdminServer.json").matches());
        assertTrue(RecordingDir.MANIFEST_FILE.matcher("recording-ms_1.json").matches());
        assertFalse(RecordingDir.MANIFEST_FILE.matcher("recording-index.json").matches());
        assertFalse(RecordingDir.MANIFEST_FILE.matcher("lineage.json").matches());
        assertFalse(RecordingDir.MANIFEST_FILE.matcher("000001-t-abc.json").matches());
    }
}
