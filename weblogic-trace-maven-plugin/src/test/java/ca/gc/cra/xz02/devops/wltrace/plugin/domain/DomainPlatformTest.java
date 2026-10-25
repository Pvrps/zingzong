package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Paths;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves each platform's syntax and rules once, side by side: file names, comment and line ending, the JAVA_OPTIONS line, the JDK assignment, and path equality. */
public class DomainPlatformTest {

    private final DomainPlatform unix = new UnixPlatform();
    private final DomainPlatform windows = new WindowsPlatform();

    @DataProvider
    public Object[][] platformFacts() {
        return new Object[][]{
                {unix, "setDomainEnv.sh", "setUserOverrides.sh", "# ", "\n", "#!/bin/sh", true},
                {windows, "setDomainEnv.cmd", "setUserOverrides.cmd", "rem ", "\r\n", "", false},
        };
    }

    @Test(dataProvider = "platformFacts")
    public void namesTheFilesAndSyntaxOfItsStartScripts(DomainPlatform platform, String domainEnv, String overrides, String comment,
                                                        String lineEnding, String header, boolean executable) {
        assertEquals(platform.domainEnvFileName(), domainEnv);
        assertEquals(platform.overridesFileName(), overrides);
        assertEquals(platform.commentPrefix(), comment);
        assertEquals(platform.lineEnding(), lineEnding);
        assertEquals(platform.createdFileHeader(), header);
        assertEquals(platform.overridesFileExecutable(), executable);
    }

    @Test
    public void appendsToJavaOptionsInEachShellsSyntax() {
        assertEquals(unix.appendToJavaOptions("-Dx=1"), "JAVA_OPTIONS=\"${JAVA_OPTIONS} -Dx=1\"");
        assertEquals(unix.blockTrailer(), java.util.Collections.singletonList("export JAVA_OPTIONS"));
        assertEquals(windows.appendToJavaOptions("-Dx=1"), "set JAVA_OPTIONS=%JAVA_OPTIONS% -Dx=1");
        assertEquals(windows.appendToJavaOptions("-Dx=a b"), "set JAVA_OPTIONS=%JAVA_OPTIONS% \"-Dx=a b\"");
        assertTrue(windows.blockTrailer().isEmpty());
    }

    @DataProvider
    public Object[][] javaHomeAssignments() {
        return new Object[][]{
                {unix, "JAVA_HOME=\"${JAVA_HOME}\"\nSUN_JAVA_HOME=\"/opt/jdk8\"\nJAVA_HOME=\"/opt/jdk-21\"\n", Optional.of(Paths.get("/opt/jdk-21"))},
                {unix, "SUN_JAVA_HOME=\"/opt/jdk8\"\nJAVA_HOME=\"${SUN_JAVA_HOME}\"\n", Optional.of(Paths.get("/opt/jdk8"))},
                {unix, "JAVA_HOME=\"${JAVA_HOME}\"\n", Optional.empty()},
                {windows, "set JAVA_HOME=%JAVA_HOME%\r\nset JAVA_HOME=C:\\Java\\jdk-21\r\n", Optional.of(Paths.get("C:\\Java\\jdk-21"))},
                {windows, "SET BEA_JAVA_HOME=C:\\Java\\jdk8\r\n", Optional.of(Paths.get("C:\\Java\\jdk8"))},
                {windows, "JAVA_HOME=/opt/jdk\n", Optional.empty()},
        };
    }

    @Test(dataProvider = "javaHomeAssignments")
    public void readsTheLastLiteralJavaHomeAssignmentInItsOwnSyntax(DomainPlatform platform, String domainEnv, Optional<java.nio.file.Path> expected) {
        assertEquals(platform.javaHomeIn(domainEnv), expected);
    }

    @Test
    public void comparesPathsCaseInsensitivelyOnWindowsOnly() {
        assertTrue(windows.sameLocation(Paths.get("C:\\Oracle\\Domains\\d1"), Paths.get("c:\\oracle\\domains\\D1")));
        assertFalse(unix.sameLocation(Paths.get("/domains/d1"), Paths.get("/domains/D1")));
        assertTrue(unix.sameLocation(Paths.get("/domains/x/../d1"), Paths.get("/domains/d1")));
    }

    @Test
    public void currentPlatformFollowsTheOperatingSystem() {
        boolean onWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(DomainPlatform.current().name(), onWindows ? "windows" : "unix");
    }
}
