package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;

import ca.gc.cra.xz02.devops.wltrace.plugin.domain.Domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the five state sentences from the facts that produce them, and how a domain's platform and JDK are read from its bin directory. */
class DomainStateTest {

    @TempDir
    Path tmp;

    private final Domain domain = new Domain(Paths.get("/domains/d1"));

    private static DomainState.Server server(String name, boolean agent) {
        return new DomainState.Server("4242", name, agent, null);
    }

    @Test
    void notEnabled() {
        DomainState s = new DomainState(false, false, false, Collections.<DomainState.Server>emptyList(), domain);
        assertEquals(DomainState.Kind.NOT_ENABLED, s.kind);
        assertTrue(s.message.startsWith("not enabled: run wltrace:enable"), s.message);
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

    @Test
    void unixDomainFromSetDomainEnvSh() throws Exception {
        Domain d = fixture("setDomainEnv.sh",
                "#!/bin/sh\nJAVA_HOME=\"${JAVA_HOME}\"\nSUN_JAVA_HOME=\"/opt/jdk8\"\nexport SUN_JAVA_HOME\nJAVA_HOME=\"/opt/jdk-21\"\nexport JAVA_HOME\n");
        assertEquals(Domain.Platform.UNIX, d.platform());
        assertEquals("setUserOverrides.sh", d.setUserOverrides().getFileName().toString());
        assertEquals(Paths.get("/opt/jdk-21"), d.domainJavaHome(), "the last literal JAVA_HOME wins over a variable reference and over SUN_JAVA_HOME");
        assertEquals(0, d.domainJavaMajor(), "no JDK at that path here");
    }

    @Test
    void windowsDomainFromSetDomainEnvCmd() throws Exception {
        Domain d = fixture("setDomainEnv.cmd",
                "@ECHO OFF\r\nset JAVA_HOME=%JAVA_HOME%\r\nset SUN_JAVA_HOME=C:\\Java\\jdk8\r\nset JAVA_HOME=C:\\Java\\jdk-21\r\nif \"%JAVA_VENDOR%\"==\"Sun\" (\r\n  set JAVA_HOME=%SUN_JAVA_HOME%\r\n)\r\n");
        assertEquals(Domain.Platform.WINDOWS, d.platform());
        assertEquals("setUserOverrides.cmd", d.setUserOverrides().getFileName().toString());
        assertEquals(d.binDir().resolve("setUserOverrides.cmd"), d.setUserOverrides());
        assertEquals(Paths.get("C:\\Java\\jdk-21"), d.domainJavaHome(), "the last literal set JAVA_HOME= wins");
        assertEquals("weblogic-trace-agent.jar", d.installedAgentJar().getFileName().toString());
        assertEquals(d.home.resolve("weblogic-trace"), d.installedAgentJar().getParent());
    }

    @Test
    void domainWithBothScriptsFollowsTheCurrentOs() throws Exception {
        Domain d = fixture("setDomainEnv.sh", "JAVA_HOME=\"/opt/jdk\"\n");
        Files.write(d.binDir().resolve("setDomainEnv.cmd"), "set JAVA_HOME=C:\\jdk\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(Domain.Platform.current(), d.platform());
        assertEquals(Domain.Platform.current().userOverrides, d.setUserOverrides().getFileName().toString());
    }

    @Test
    void domainWithoutScriptsFollowsTheCurrentOsAndHasNoJdk() throws Exception {
        Path home = tmp.resolve("bare");
        Files.createDirectories(home.resolve("config"));
        Files.write(home.resolve("config").resolve("config.xml"), "<domain><domain-version>14.1.2.0.0</domain-version></domain>".getBytes(StandardCharsets.UTF_8));
        Domain d = new Domain(home);
        d.verify();
        assertEquals("14.1.2.0.0", d.webLogicVersion());
        assertEquals(Domain.Platform.current(), d.platform());
        assertNull(d.domainJavaHome());
    }

    @Test
    void jvmMatchingNormalisesAndIgnoresCaseOnWindowsOnly() {
        assertTrue(Domain.sameLocation(Paths.get("C:\\Oracle\\Domains\\base_domain"), Paths.get("c:\\oracle\\domains\\BASE_DOMAIN"), Domain.Platform.WINDOWS));
        assertFalse(Domain.sameLocation(Paths.get("/domains/d1"), Paths.get("/domains/D1"), Domain.Platform.UNIX));
        assertTrue(Domain.sameLocation(Paths.get("/domains/x/../d1"), Paths.get("/domains/d1"), Domain.Platform.UNIX));
        assertTrue(domain.isHome(Paths.get("/domains/./d1")));
        assertFalse(domain.isHome(Paths.get("/domains/d2")));
    }

    private Domain fixture(String domainEnv, String content) throws Exception {
        Path home = tmp.resolve("domain-" + domainEnv.replace('.', '-'));
        Files.createDirectories(home.resolve("config"));
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("config").resolve("config.xml"), "<domain><domain-version>12.2.1.4.0</domain-version></domain>".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("bin").resolve(domainEnv), content.getBytes(StandardCharsets.UTF_8));
        Domain d = new Domain(home);
        d.verify();
        return d;
    }
}
