package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves how a domain's platform and JDK are read from its bin directory, and how JVMs are matched to a domain home. */
class DomainTest {

    @TempDir
    Path tmp;

    @Test
    void javaMajorFromVersionStrings() {
        assertEquals(8, DomainJdk.majorOf("1.8.0_392"));
        assertEquals(11, DomainJdk.majorOf("11.0.21"));
        assertEquals(21, DomainJdk.majorOf("21.0.12.1"));
        assertEquals(0, DomainJdk.majorOf(""));
    }

    @Test
    void unixDomainFromSetDomainEnvSh() throws Exception {
        Domain domain = fixture("setDomainEnv.sh",
                "#!/bin/sh\nJAVA_HOME=\"${JAVA_HOME}\"\nSUN_JAVA_HOME=\"/opt/jdk8\"\nexport SUN_JAVA_HOME\nJAVA_HOME=\"/opt/jdk-21\"\nexport JAVA_HOME\n");
        assertTrue(domain.platform() instanceof UnixPlatform);
        assertEquals("setUserOverrides.sh", domain.overridesFile().getFileName().toString());
        DomainJdk jdk = new DomainJdk(domain);
        assertEquals(Optional.of(Paths.get("/opt/jdk-21")), jdk.javaHome(), "the last literal JAVA_HOME wins over a variable reference and over SUN_JAVA_HOME");
        assertEquals(0, jdk.majorVersion(), "no JDK at that path here");
    }

    @Test
    void windowsDomainFromSetDomainEnvCmd() throws Exception {
        Domain domain = fixture("setDomainEnv.cmd",
                "@ECHO OFF\r\nset JAVA_HOME=%JAVA_HOME%\r\nset SUN_JAVA_HOME=C:\\Java\\jdk8\r\nset JAVA_HOME=C:\\Java\\jdk-21\r\nif \"%JAVA_VENDOR%\"==\"Sun\" (\r\n  set JAVA_HOME=%SUN_JAVA_HOME%\r\n)\r\n");
        assertTrue(domain.platform() instanceof WindowsPlatform);
        assertEquals("setUserOverrides.cmd", domain.overridesFile().getFileName().toString());
        assertEquals(domain.binDirectory().resolve("setUserOverrides.cmd"), domain.overridesFile());
        assertEquals(Optional.of(Paths.get("C:\\Java\\jdk-21")), new DomainJdk(domain).javaHome(), "the last literal set JAVA_HOME= wins");
        assertEquals("weblogic-trace-agent.jar", domain.installedAgentJar().getFileName().toString());
        assertEquals(domain.home.resolve("weblogic-trace"), domain.installedAgentJar().getParent());
    }

    @Test
    void domainWithBothScriptsFollowsTheCurrentOs() throws Exception {
        Domain domain = fixture("setDomainEnv.sh", "JAVA_HOME=\"/opt/jdk\"\n");
        Files.write(domain.binDirectory().resolve("setDomainEnv.cmd"), "set JAVA_HOME=C:\\jdk\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(DomainPlatform.current().name(), new Domain(domain.home).platform().name());
        assertEquals(DomainPlatform.current().overridesFileName(), new Domain(domain.home).overridesFile().getFileName().toString());
    }

    @Test
    void domainWithoutScriptsFollowsTheCurrentOsAndHasNoJdk() throws Exception {
        Path home = tmp.resolve("bare");
        Files.createDirectories(home.resolve("config"));
        Files.write(home.resolve("config").resolve("config.xml"), "<domain><domain-version>14.1.2.0.0</domain-version></domain>".getBytes(StandardCharsets.UTF_8));
        Domain domain = new Domain(home);
        domain.verify();
        assertEquals("14.1.2.0.0", domain.webLogicVersion());
        assertEquals(DomainPlatform.current().name(), domain.platform().name());
        assertFalse(new DomainJdk(domain).javaHome().isPresent());
    }

    @Test
    void jvmMatchingNormalisesAndIgnoresCaseOnWindowsOnly() {
        assertTrue(new WindowsPlatform().sameLocation(Paths.get("C:\\Oracle\\Domains\\base_domain"), Paths.get("c:\\oracle\\domains\\BASE_DOMAIN")));
        assertFalse(new UnixPlatform().sameLocation(Paths.get("/domains/d1"), Paths.get("/domains/D1")));
        assertTrue(new UnixPlatform().sameLocation(Paths.get("/domains/x/../d1"), Paths.get("/domains/d1")));
        Domain domain = new Domain(Paths.get("/domains/d1"));
        assertTrue(domain.isHome(Paths.get("/domains/./d1")));
        assertFalse(domain.isHome(Paths.get("/domains/d2")));
    }

    private Domain fixture(String domainEnv, String content) throws Exception {
        Path home = tmp.resolve("domain-" + domainEnv.replace('.', '-'));
        Files.createDirectories(home.resolve("config"));
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("config").resolve("config.xml"), "<domain><domain-version>12.2.1.4.0</domain-version></domain>".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("bin").resolve(domainEnv), content.getBytes(StandardCharsets.UTF_8));
        Domain domain = new Domain(home);
        domain.verify();
        return domain;
    }
}
