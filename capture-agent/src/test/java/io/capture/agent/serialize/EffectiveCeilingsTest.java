package io.capture.agent.serialize;

import io.capture.agent.AgentConfig;
import io.capture.agent.span.Recorder;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ceilings the agent actually runs with are the documented ones. Until the plugin
 * release, AgentConfig's own defaults (depth 12, 500 elements) silently overrode
 * SerializerConfig's 64 / 100 000 through Recorder.configure; this fails if that ever
 * happens again, through whatever path.
 */
class EffectiveCeilingsTest {

    @Test
    void recorderRunsWithTheDocumentedCeilings() {
        Recorder.configure(AgentConfig.parse("out=" + new File(System.getProperty("java.io.tmpdir"), "ceilings-test").getAbsolutePath()));
        SerializerConfig effective = ((JacksonGraphSerializer) Recorder.serializer()).config();
        assertEquals(SerializerConfig.DEFAULT_MAX_DEPTH, effective.maxDepth);
        assertEquals(SerializerConfig.DEFAULT_MAX_ELEMENTS, effective.maxElements);
        assertEquals(SerializerConfig.DEFAULT_MAX_STRING_LENGTH, effective.maxStringLength);
        assertEquals(SerializerConfig.DEFAULT_MAX_BYTES, effective.maxBytes);
        assertTrue(effective.typeAllowList.allows("com.acme.Anything"), "no type allow-list in production");
    }

    @Test
    void documentedCeilingsAreNotReachableByRealPayloads() {
        assertTrue(SerializerConfig.DEFAULT_MAX_DEPTH >= 64);
        assertTrue(SerializerConfig.DEFAULT_MAX_ELEMENTS >= 100_000);
        assertTrue(SerializerConfig.DEFAULT_MAX_STRING_LENGTH >= 16 * 1024 * 1024);
        assertTrue(SerializerConfig.DEFAULT_MAX_BYTES >= 64 * 1024 * 1024);
    }
}
