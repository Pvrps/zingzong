package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the default ceilings are the documented ones and out of reach of real payloads. */
class SerializationCeilingsTest {

    @Test
    void defaultsAreTheDocumentedCeilings() {
        SerializationCeilings ceilings = new SerializationCeilings();
        assertEquals(SerializationCeilings.DEFAULT_MAX_DEPTH, ceilings.maxDepth);
        assertEquals(SerializationCeilings.DEFAULT_MAX_ELEMENTS, ceilings.maxElements);
        assertEquals(SerializationCeilings.DEFAULT_MAX_STRING_LENGTH, ceilings.maxStringLength);
        assertEquals(SerializationCeilings.DEFAULT_MAX_BYTES, ceilings.maxBytes);
    }

    @Test
    void documentedCeilingsAreNotReachableByRealPayloads() {
        assertTrue(SerializationCeilings.DEFAULT_MAX_DEPTH >= 64);
        assertTrue(SerializationCeilings.DEFAULT_MAX_ELEMENTS >= 100_000);
        assertTrue(SerializationCeilings.DEFAULT_MAX_STRING_LENGTH >= 16 * 1024 * 1024);
        assertTrue(SerializationCeilings.DEFAULT_MAX_BYTES >= 64 * 1024 * 1024);
    }
}
