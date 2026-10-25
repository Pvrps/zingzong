package ca.gc.cra.xz02.devops.wltrace.plugin;

import ca.gc.cra.xz02.devops.wltrace.plugin.OverridesBlock.Syntax;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the block is written and removed in both syntaxes without touching a byte of the rest of the file. */
class OverridesBlockTest {

    @TempDir
    Path tmp;

    private final Path jar = Paths.get("/domain/weblogic-trace/weblogic-trace-agent.jar");
    private final Path out = Paths.get("/home/me/.weblogic-trace/domain");
    private final Path winJar = Paths.get("C:\\Oracle\\domains\\base_domain\\weblogic-trace\\weblogic-trace-agent.jar");
    private final Path winOut = Paths.get("C:\\Users\\me\\.weblogic-trace\\base_domain");

    @Test
    void createsTheShellFileWhenAbsentAndRemovesItAgain() throws Exception {
        Path f = tmp.resolve("bin").resolve("setUserOverrides.sh");
        assertTrue(OverridesBlock.install(f, Syntax.SH, jar, out, 21));
        String text = read(f);
        assertTrue(text.startsWith("#!/bin/sh\n" + Syntax.SH.begin + "\n"), text);
        assertTrue(text.contains("JAVA_OPTIONS=\"${JAVA_OPTIONS} -javaagent:" + jar + "=out=" + out + "\"\n"), text);
        assertTrue(text.contains("JAVA_OPTIONS=\"${JAVA_OPTIONS} --add-opens=java.base/java.lang=ALL-UNNAMED\"\n"), text);
        assertTrue(text.contains("\nexport JAVA_OPTIONS\n"), text);
        assertTrue(text.endsWith(Syntax.SH.end + "\n"), text);
        assertFalse(text.contains("\r"), "shell files get LF");
        assertTrue(OverridesBlock.isPresent(f));
        assertTrue(OverridesBlock.remove(f, Syntax.SH));
        assertFalse(Files.exists(f), "enable created it, disable deletes it");
    }

    @Test
    void leavesAnExistingShellFileByteForByte() throws Exception {
        for (String original : new String[]{"# mine\nJAVA_OPTIONS=\"$JAVA_OPTIONS -Dx=1\"\n", "# no trailing newline\nexport Y=2"}) {
            Path f = tmp.resolve("setUserOverrides.sh");
            Files.write(f, original.getBytes(StandardCharsets.UTF_8));
            assertFalse(OverridesBlock.install(f, Syntax.SH, jar, out, 8));
            String enabled = read(f);
            assertTrue(enabled.startsWith(original.endsWith("\n") ? original : original + "\n"), enabled);
            assertFalse(enabled.contains("--add-opens"), "JDK 8: no add-opens");
            assertFalse(OverridesBlock.install(f, Syntax.SH, jar, out, 11));
            String updated = read(f);
            assertEquals(1, count(updated, Syntax.SH.begin), "a second enable updates in place");
            assertTrue(updated.contains("--add-opens"), "JDK 11: add-opens");
            assertTrue(OverridesBlock.remove(f, Syntax.SH));
            assertEquals(original, read(f), "only the block and its separator are removed");
            Files.delete(f);
        }
    }

    @Test
    void createsTheCmdFileWhenAbsentAndRemovesItAgain() throws Exception {
        Path f = tmp.resolve("bin").resolve("setUserOverrides.cmd");
        assertTrue(OverridesBlock.install(f, Syntax.CMD, winJar, winOut, 21));
        String text = read(f);
        assertTrue(text.startsWith("rem >>> weblogic-trace >>>\r\n"), text);
        assertTrue(text.contains("rem weblogic-trace created this file; wltrace:disable removes it\r\n"), text);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% -javaagent:" + winJar + "=out=" + winOut + "\r\n"), text);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% --add-opens=java.base/java.lang=ALL-UNNAMED\r\n"), text);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% --add-opens=java.logging/java.util.logging=ALL-UNNAMED\r\n"), text);
        assertFalse(text.contains("export"), "no shell syntax in a cmd file");
        assertFalse(text.contains("#"), text);
        assertTrue(text.endsWith("rem <<< weblogic-trace <<<\r\n"), text);
        assertEquals(count(text, "\n"), count(text, "\r\n"), "every line ends in CRLF");
        assertTrue(OverridesBlock.isPresent(f));
        assertTrue(OverridesBlock.remove(f, Syntax.CMD));
        assertFalse(Files.exists(f), "enable created it, disable deletes it");
    }

    @Test
    void leavesAnExistingCmdFileByteForByte() throws Exception {
        for (String original : new String[]{"@echo off\r\nset JAVA_OPTIONS=%JAVA_OPTIONS% -Dx=1\r\n", "rem no trailing newline\r\nset Y=2"}) {
            Path f = tmp.resolve("setUserOverrides.cmd");
            Files.write(f, original.getBytes(StandardCharsets.UTF_8));
            assertFalse(OverridesBlock.install(f, Syntax.CMD, winJar, winOut, 8));
            String enabled = read(f);
            assertTrue(enabled.startsWith(original.endsWith("\n") ? original : original + "\r\n"), enabled);
            assertTrue(enabled.contains("\r\nrem >>> weblogic-trace >>>\r\n"), enabled);
            assertFalse(enabled.contains("--add-opens"), "JDK 8: no add-opens");
            assertFalse(OverridesBlock.install(f, Syntax.CMD, winJar, winOut, 21));
            String updated = read(f);
            assertEquals(1, count(updated, Syntax.CMD.begin), "a second enable updates in place");
            assertTrue(updated.contains("--add-opens"), "JDK 21: add-opens");
            assertTrue(OverridesBlock.remove(f, Syntax.CMD));
            assertEquals(original, read(f), "only the block and its separator are removed");
            Files.delete(f);
        }
    }

    @Test
    void cmdQuotesAnOptionWithSpacesOrSpecialCharacters() {
        Path spaced = Paths.get("C:\\Program Files\\Oracle\\domain\\weblogic-trace\\weblogic-trace-agent.jar");
        String text = OverridesBlock.text(Syntax.CMD, spaced, winOut, 8, false, false);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% \"-javaagent:" + spaced + "=out=" + winOut + "\"\r\n"), text);
        assertEquals("--add-opens=java.base/java.lang=ALL-UNNAMED", OverridesBlock.quoteForCmd("--add-opens=java.base/java.lang=ALL-UNNAMED"));
        assertEquals("\"a&b\"", OverridesBlock.quoteForCmd("a&b"));
    }

    @Test
    void javaOptionsLineForStartupProperties() {
        String line = OverridesBlock.javaOptions(jar, out, 21);
        assertTrue(line.startsWith("-javaagent:" + jar + "=out=" + out + " --add-opens="), line);
        assertEquals("-javaagent:" + jar + "=out=" + out, OverridesBlock.javaOptions(jar, out, 8));
        assertEquals("-javaagent:C:\\\\Oracle\\\\domains\\\\base_domain\\\\weblogic-trace\\\\weblogic-trace-agent.jar=out=C:\\\\Users\\\\me\\\\.weblogic-trace\\\\base_domain",
                EnableMojo.propertiesValue(OverridesBlock.javaOptions(winJar, winOut, 8)), "backslashes doubled for a .properties file");
    }

    private static String read(Path f) throws Exception {
        return new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
