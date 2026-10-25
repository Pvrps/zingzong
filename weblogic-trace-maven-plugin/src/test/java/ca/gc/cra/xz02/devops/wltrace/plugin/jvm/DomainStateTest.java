package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain;
import org.testng.annotations.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves the five state sentences from the facts that produce them. */
public class DomainStateTest {

    private final Domain domain = new Domain(Paths.get("/domains/d1"));

    private static DomainState.Server server(String name, boolean agent) {
        return new DomainState.Server("4242", name, agent, null);
    }

    @Test
    public void notEnabled() {
        DomainState state = new DomainState(false, false, false, Collections.emptyList(), domain);
        assertEquals(state.kind, DomainState.Kind.NOT_ENABLED);
        assertTrue(state.message.startsWith("not enabled: run wltrace:enable"), state.message);
        DomainState stale = new DomainState(true, true, false, Collections.emptyList(), domain);
        assertEquals(stale.kind, DomainState.Kind.NOT_ENABLED);
        assertTrue(stale.message.contains("not the one this plugin ships"), stale.message);
    }

    @Test
    public void enabledServerNotRunning() {
        DomainState state = new DomainState(true, true, true, Collections.emptyList(), domain);
        assertEquals(state.kind, DomainState.Kind.ENABLED_SERVER_DOWN);
        assertTrue(state.message.startsWith("enabled, server not running"), state.message);
        assertFalse(state.ready());
    }

    @Test
    public void enabledServerWithoutAgent() {
        List<DomainState.Server> servers = Arrays.asList(server("AdminServer", true), server("ms1", false));
        DomainState state = new DomainState(true, true, true, servers, domain);
        assertEquals(state.kind, DomainState.Kind.ENABLED_SERVER_NO_AGENT);
        assertTrue(state.message.startsWith("enabled, server running without the agent, restart it: ms1 (pid 4242)"), state.message);
        assertTrue(state.message.endsWith("nothing is recorded until then"), state.message);
    }

    @Test
    public void disabledButLoaded() {
        DomainState state = new DomainState(false, false, false, Collections.singletonList(server("AdminServer", true)), domain);
        assertEquals(state.kind, DomainState.Kind.DISABLED_AGENT_LOADED);
        assertTrue(state.message.startsWith("disabled on disk, agent still loaded until restart: AdminServer"), state.message);
        assertTrue(state.anyAgentLoaded());
    }

    @Test
    public void ready() {
        DomainState state = new DomainState(true, true, true, Collections.singletonList(server("AdminServer", true)), domain);
        assertEquals(state.kind, DomainState.Kind.READY);
        assertEquals(state.message, "ready: AdminServer (pid 4242)");
        assertTrue(state.ready());
    }
}
