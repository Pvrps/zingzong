package io.capture.plugin;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The five sentences, from the facts that produce them. */
class DomainStateTest {

    private final Domain domain = new Domain(new File("/domains/d1"));

    private static DomainState.Server server(String name, boolean agent) {
        return new DomainState.Server("4242", name, agent, null);
    }

    @Test
    void notEnabled() {
        DomainState s = new DomainState(false, false, false, Collections.<DomainState.Server>emptyList(), domain);
        assertEquals(DomainState.Kind.NOT_ENABLED, s.kind);
        assertTrue(s.message.startsWith("not enabled: run capture:enable"), s.message);
        DomainState stale = new DomainState(true, true, false, Collections.<DomainState.Server>emptyList(), domain);
        assertEquals(DomainState.Kind.NOT_ENABLED, stale.kind);
        assertTrue(stale.message.contains("not the one this plugin ships"), stale.message);
    }

    @Test
    void enabledServerNotRunning() {
        DomainState s = new DomainState(true, true, true, Collections.<DomainState.Server>emptyList(), domain);
        assertEquals(DomainState.Kind.ENABLED_SERVER_DOWN, s.kind);
        assertTrue(s.message.startsWith("enabled, server not running"), s.message);
        assertFalse(s.ready());
    }

    @Test
    void enabledServerWithoutAgent() {
        List<DomainState.Server> servers = Arrays.asList(server("AdminServer", true), server("ms1", false));
        DomainState s = new DomainState(true, true, true, servers, domain);
        assertEquals(DomainState.Kind.ENABLED_SERVER_NO_AGENT, s.kind);
        assertTrue(s.message.startsWith("enabled, server running without the agent, restart it: ms1 (pid 4242)"), s.message);
        assertTrue(s.message.endsWith("nothing is captured until then"), s.message);
    }

    @Test
    void disabledButLoaded() {
        DomainState s = new DomainState(false, false, false, Collections.singletonList(server("AdminServer", true)), domain);
        assertEquals(DomainState.Kind.DISABLED_AGENT_LOADED, s.kind);
        assertTrue(s.message.startsWith("disabled on disk, agent still loaded until restart: AdminServer"), s.message);
        assertTrue(s.anyAgentLoaded());
    }

    @Test
    void ready() {
        DomainState s = new DomainState(true, true, true, Collections.singletonList(server("AdminServer", true)), domain);
        assertEquals(DomainState.Kind.READY, s.kind);
        assertEquals("ready: AdminServer (pid 4242)", s.message);
        assertTrue(s.ready());
    }

    @Test
    void javaMajorFromVersionStrings() {
        assertEquals(8, Domain.majorOf("1.8.0_392"));
        assertEquals(11, Domain.majorOf("11.0.21"));
        assertEquals(21, Domain.majorOf("21.0.12.1"));
        assertEquals(0, Domain.majorOf(""));
    }
}
