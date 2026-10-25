package io.capture.agent;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves that {@code out} is the agent's only argument. */
class AgentConfigTest {

    @Test
    void outIsTheOnlyArgument() {
        assertEquals(new File(System.getProperty("java.io.tmpdir"), "capture"), AgentConfig.parse(null).outDir);
        assertEquals(new File("/tmp/x"), AgentConfig.parse(" out=/tmp/x ,").outDir);
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hook=x"), "no hook override");
    }
}
