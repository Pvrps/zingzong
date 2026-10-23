package io.capture.agent;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @Test
    void defaultsWhenNoArguments() {
        AgentConfig config = AgentConfig.parse(null);
        assertFalse(config.enabled);
        assertEquals(AgentConfig.HOOK_ANNOTATION, config.hook);
        assertTrue(config.accepts("com.acme.Anything"));
    }

    @Test
    void parsesKeyValuePairs() {
        AgentConfig config = AgentConfig.parse("out=/tmp/x, enabled=true,include=com.acme.*|org.foo.?ar,exclude=com.acme.internal.*,hook=container");
        assertEquals(new File("/tmp/x"), config.outDir);
        assertTrue(config.enabled);
        assertEquals(AgentConfig.HOOK_CONTAINER, config.hook);
        assertTrue(config.accepts("com.acme.orders.OrderServiceBean"));
        assertTrue(config.accepts("org.foo.Bar"));
        assertFalse(config.accepts("org.foo.Bazar"));
        assertFalse(config.accepts("com.acme.internal.Helper"));
        assertFalse(config.accepts("com.other.Bean"));
    }

    @Test
    void rejectsUnknownKeysAndHooks() {
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("bogus=1"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("hook=magic"));
    }
}
