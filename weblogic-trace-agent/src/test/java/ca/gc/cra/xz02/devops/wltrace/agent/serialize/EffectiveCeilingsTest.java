package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import ca.gc.cra.xz02.devops.wltrace.agent.record.Recorder;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the recorder runs with the documented ceilings and that those are out of reach of real payloads. */
class EffectiveCeilingsTest {

    @Test
    void recorderRunsWithTheDocumentedCeilings() {
        Recorder.configure(new File(System.getProperty("java.io.tmpdir"), "ceilings-test"));
        SerializerConfig effective = ((JacksonGraphSerializer) Recorder.serializer()).config();
        assertEquals(SerializerConfig.DEFAULT_MAX_DEPTH, effective.maxDepth);
        assertEquals(SerializerConfig.DEFAULT_MAX_ELEMENTS, effective.maxElements);
        assertEquals(SerializerConfig.DEFAULT_MAX_STRING_LENGTH, effective.maxStringLength);
        assertEquals(SerializerConfig.DEFAULT_MAX_BYTES, effective.maxBytes);
    }

    @Test
    void documentedCeilingsAreNotReachableByRealPayloads() {
        assertTrue(SerializerConfig.DEFAULT_MAX_DEPTH >= 64);
        assertTrue(SerializerConfig.DEFAULT_MAX_ELEMENTS >= 100_000);
        assertTrue(SerializerConfig.DEFAULT_MAX_STRING_LENGTH >= 16 * 1024 * 1024);
        assertTrue(SerializerConfig.DEFAULT_MAX_BYTES >= 64 * 1024 * 1024);
    }
}
