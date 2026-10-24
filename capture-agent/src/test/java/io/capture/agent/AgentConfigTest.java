package io.capture.agent;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void outIsTheOnlyArgument() {
        assertEquals(new File(System.getProperty("java.io.tmpdir"), "capture"), AgentConfig.parse(null).outDir);
        assertEquals(new File("/tmp/x"), AgentConfig.parse(" out=/tmp/x ,").outDir);
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hook=x"), "no hook override");
    }

    @Test
    void hookIsChosenByServer() {
        assertFalse(ServerDetection.useContainerHook(ServerDetection.Server.UNKNOWN, true));
        assertFalse(ServerDetection.useContainerHook(ServerDetection.Server.WEBLOGIC, false));
        assertTrue(ServerDetection.useContainerHook(ServerDetection.Server.WEBLOGIC, true));
        assertEquals(ServerDetection.Server.UNKNOWN, ServerDetection.detect(), "unit tests do not run inside WebLogic");
    }
}
