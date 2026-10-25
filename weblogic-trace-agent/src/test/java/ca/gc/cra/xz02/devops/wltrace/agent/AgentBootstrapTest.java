package ca.gc.cra.xz02.devops.wltrace.agent;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that {@code out} is the agent's only argument and that the agent stays inert outside WebLogic. */
class AgentBootstrapTest {

    @Test
    void outIsTheOnlyArgument() {
        assertEquals(new File(System.getProperty("java.io.tmpdir"), "capture"), Agent.outDir(null));
        assertEquals(new File("/tmp/x"), Agent.outDir(" out=/tmp/x ,"));
        assertThrows(IllegalArgumentException.class, () -> Agent.outDir("bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> Agent.outDir("hook=x"), "no hook override");
    }

    @Test
    void inertWithoutWebLogicActiveWithIt() {
        String reason = AgentBootstrap.inertReason();
        assertTrue(reason != null && reason.startsWith("not a WebLogic server"), reason);
        System.setProperty("weblogic.home", "/opt/wls");
        try {
            assertNull(AgentBootstrap.inertReason(), "the test class path carries stubs of every container target");
        } finally {
            System.clearProperty("weblogic.home");
        }
    }
}
