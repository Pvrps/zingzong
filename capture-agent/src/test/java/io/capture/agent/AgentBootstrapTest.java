package io.capture.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the agent stays inert outside WebLogic and activates when WebLogic and its container classes are present. */
class AgentBootstrapTest {

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
