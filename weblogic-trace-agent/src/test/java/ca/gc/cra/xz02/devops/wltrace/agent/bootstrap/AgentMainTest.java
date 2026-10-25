package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves that {@code out} is the agent's only argument. */
class AgentMainTest {

    @Test
    void outIsTheOnlyArgument() {
        assertEquals(new File(System.getProperty("java.io.tmpdir"), "weblogic-trace"), AgentMain.outputDirectory(null));
        assertEquals(new File("/tmp/x"), AgentMain.outputDirectory(" out=/tmp/x ,"));
        assertThrows(IllegalArgumentException.class, () -> AgentMain.outputDirectory("bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> AgentMain.outputDirectory("hook=x"), "no hook override");
    }
}
