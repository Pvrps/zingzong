package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import org.testng.annotations.Test;

import java.io.File;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

/** Proves that {@code out} is the agent's only argument. */
public class AgentMainTest {

    @Test
    public void outIsTheOnlyArgument() {
        assertEquals(AgentMain.outputDirectory(null), new File(System.getProperty("java.io.tmpdir"), "weblogic-trace"));
        assertEquals(AgentMain.outputDirectory(" out=/tmp/x ,"), new File("/tmp/x"));
        assertThrows(IllegalArgumentException.class, () -> AgentMain.outputDirectory("bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> AgentMain.outputDirectory("hook=x"));
    }
}
