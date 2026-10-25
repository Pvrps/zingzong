package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the five state sentences from the facts that produce them. */
class DomainStateTest {

    private final Domain domain = new Domain(Paths.get("/domains/d1"));

    private static DomainState.Server server(String name, boolean agent) {
        return new DomainState.Server("4242", name, agent, null);
    }

    @Test
    void notEnabled() {
        DomainState state = new DomainState(false, false, false, Collections.emptyList(), domain);
        assertEquals(DomainState.Kind.NOT_ENABLED, state.kind);
        assertTrue(state.message.startsWith("not enabled: run wltrace:enable"), state.message);
        DomainState stale = new DomainState(true, true, false, Collections.emptyList(), domain);
        assertEquals(DomainState.Kind.NOT_ENABLED, stale.kind);
        assertTrue(stale.message.contains("not the one this plugin ships"), stale.message);
    }

    @Test
    void enabledServerNotRunning() {
        DomainState state = new DomainState(true, true, true, Collections.emptyList(), domain);
        assertEquals(DomainState.Kind.ENABLED_SERVER_DOWN, state.kind);
        assertTrue(state.message.startsWith("enabled, server not running"), state.message);
        assertFalse(state.ready());
    }

    @Test
    void enabledServerWithoutAgent() {
        List<DomainState.Server> servers = Arrays.asList(server("AdminServer", true), server("ms1", false));
        DomainState state = new DomainState(true, true, true, servers, domain);
        assertEquals(DomainState.Kind.ENABLED_SERVER_NO_AGENT, state.kind);
        assertTrue(state.message.startsWith("enabled, server running without the agent, restart it: ms1 (pid 4242)"), state.message);
        assertTrue(state.message.endsWith("nothing is recorded until then"), state.message);
    }

    @Test
    void disabledButLoaded() {
        DomainState state = new DomainState(false, false, false, Collections.singletonList(server("AdminServer", true)), domain);
        assertEquals(DomainState.Kind.DISABLED_AGENT_LOADED, state.kind);
        assertTrue(state.message.startsWith("disabled on disk, agent still loaded until restart: AdminServer"), state.message);
        assertTrue(state.anyAgentLoaded());
    }

    @Test
    void ready() {
        DomainState state = new DomainState(true, true, true, Collections.singletonList(server("AdminServer", true)), domain);
        assertEquals(DomainState.Kind.READY, state.kind);
        assertEquals("ready: AdminServer (pid 4242)", state.message);
        assertTrue(state.ready());
    }
}
