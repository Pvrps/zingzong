package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Proves that the default ceilings are the documented ones and out of reach of real payloads. */
public class SerializationCeilingsTest {

    @Test
    public void defaultsAreTheDocumentedCeilings() {
        SerializationCeilings ceilings = new SerializationCeilings();
        assertEquals(ceilings.maxDepth, SerializationCeilings.DEFAULT_MAX_DEPTH);
        assertEquals(ceilings.maxElements, SerializationCeilings.DEFAULT_MAX_ELEMENTS);
        assertEquals(ceilings.maxStringLength, SerializationCeilings.DEFAULT_MAX_STRING_LENGTH);
        assertEquals(ceilings.maxBytes, SerializationCeilings.DEFAULT_MAX_BYTES);
    }

    @Test
    public void documentedCeilingsAreNotReachableByRealPayloads() {
        assertTrue(SerializationCeilings.DEFAULT_MAX_DEPTH >= 64);
        assertTrue(SerializationCeilings.DEFAULT_MAX_ELEMENTS >= 100_000);
        assertTrue(SerializationCeilings.DEFAULT_MAX_STRING_LENGTH >= 16 * 1024 * 1024);
        assertTrue(SerializationCeilings.DEFAULT_MAX_BYTES >= 64 * 1024 * 1024);
    }
}
