package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves how a domain's platform and JDK are read from its bin directory, and how JVMs are matched to a domain home. */
public class DomainTest {

    private Path tmp;

    @BeforeMethod
    public void temporaryDirectory() throws Exception {
        tmp = Files.createTempDirectory("weblogic-trace-test");
    }

    @Test
    public void javaMajorFromVersionStrings() {
        assertEquals(DomainJdk.majorOf("1.8.0_392"), 8);
        assertEquals(DomainJdk.majorOf("11.0.21"), 11);
        assertEquals(DomainJdk.majorOf("21.0.12.1"), 21);
        assertEquals(DomainJdk.majorOf(""), 0);
    }

    @Test
    public void unixDomainFromSetDomainEnvSh() throws Exception {
        Domain domain = fixture("setDomainEnv.sh",
                "#!/bin/sh\nJAVA_HOME=\"${JAVA_HOME}\"\nSUN_JAVA_HOME=\"/opt/jdk8\"\nexport SUN_JAVA_HOME\nJAVA_HOME=\"/opt/jdk-21\"\nexport JAVA_HOME\n");
        assertTrue(domain.platform() instanceof UnixPlatform);
        assertEquals(domain.overridesFile().getFileName().toString(), "setUserOverrides.sh");
        DomainJdk jdk = new DomainJdk(domain);
        assertEquals(jdk.javaHome(), Optional.of(Paths.get("/opt/jdk-21")), "the last literal JAVA_HOME wins over a variable reference and over SUN_JAVA_HOME");
        assertEquals(jdk.majorVersion(), 0, "no JDK at that path here");
    }

    @Test
    public void windowsDomainFromSetDomainEnvCmd() throws Exception {
        Domain domain = fixture("setDomainEnv.cmd",
                "@ECHO OFF\r\nset JAVA_HOME=%JAVA_HOME%\r\nset SUN_JAVA_HOME=C:\\Java\\jdk8\r\nset JAVA_HOME=C:\\Java\\jdk-21\r\nif \"%JAVA_VENDOR%\"==\"Sun\" (\r\n  set JAVA_HOME=%SUN_JAVA_HOME%\r\n)\r\n");
        assertTrue(domain.platform() instanceof WindowsPlatform);
        assertEquals(domain.overridesFile().getFileName().toString(), "setUserOverrides.cmd");
        assertEquals(domain.overridesFile(), domain.binDirectory().resolve("setUserOverrides.cmd"));
        assertEquals(new DomainJdk(domain).javaHome(), Optional.of(Paths.get("C:\\Java\\jdk-21")), "the last literal set JAVA_HOME= wins");
        assertEquals(domain.installedAgentJar().getFileName().toString(), "weblogic-trace-agent.jar");
        assertEquals(domain.installedAgentJar().getParent(), domain.home.resolve("weblogic-trace"));
    }

    @Test
    public void domainWithBothScriptsFollowsTheCurrentOs() throws Exception {
        Domain domain = fixture("setDomainEnv.sh", "JAVA_HOME=\"/opt/jdk\"\n");
        Files.write(domain.binDirectory().resolve("setDomainEnv.cmd"), "set JAVA_HOME=C:\\jdk\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(new Domain(domain.home).platform().name(), DomainPlatform.current().name());
        assertEquals(new Domain(domain.home).overridesFile().getFileName().toString(), DomainPlatform.current().overridesFileName());
    }

    @Test
    public void domainWithoutScriptsFollowsTheCurrentOsAndHasNoJdk() throws Exception {
        Path home = tmp.resolve("bare");
        Files.createDirectories(home.resolve("config"));
        Files.write(home.resolve("config").resolve("config.xml"), "<domain><domain-version>14.1.2.0.0</domain-version></domain>".getBytes(StandardCharsets.UTF_8));
        Domain domain = new Domain(home);
        domain.verify();
        assertEquals(domain.webLogicVersion(), "14.1.2.0.0");
        assertEquals(domain.platform().name(), DomainPlatform.current().name());
        assertFalse(new DomainJdk(domain).javaHome().isPresent());
    }

    @Test
    public void jvmMatchingNormalisesAndIgnoresCaseOnWindowsOnly() {
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
