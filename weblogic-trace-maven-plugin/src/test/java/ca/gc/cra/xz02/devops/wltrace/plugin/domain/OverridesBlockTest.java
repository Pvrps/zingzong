package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the block is written and removed in both platforms' syntax without touching a byte of the rest of the file. */
class OverridesBlockTest {

    @TempDir
    Path tmp;

    private final OverridesBlock unixBlock = new OverridesBlock(new UnixPlatform());
    private final OverridesBlock windowsBlock = new OverridesBlock(new WindowsPlatform());
    private final Path jar = Paths.get("/domain/weblogic-trace/weblogic-trace-agent.jar");
    private final Path out = Paths.get("/home/me/.weblogic-trace/domain");
    private final Path windowsJar = Paths.get("C:\\Oracle\\domains\\base_domain\\weblogic-trace\\weblogic-trace-agent.jar");
    private final Path windowsOut = Paths.get("C:\\Users\\me\\.weblogic-trace\\base_domain");

    @Test
    void createsTheShellFileWhenAbsentAndRemovesItAgain() throws Exception {
        Path file = tmp.resolve("bin").resolve("setUserOverrides.sh");
        assertTrue(unixBlock.install(file, jar, out, 21));
        String text = read(file);
        assertTrue(text.startsWith("#!/bin/sh\n# >>> weblogic-trace >>>\n"), text);
        assertTrue(text.contains("JAVA_OPTIONS=\"${JAVA_OPTIONS} -javaagent:" + jar + "=out=" + out + "\"\n"), text);
        assertTrue(text.contains("JAVA_OPTIONS=\"${JAVA_OPTIONS} --add-opens=java.base/java.lang=ALL-UNNAMED\"\n"), text);
        assertTrue(text.contains("\nexport JAVA_OPTIONS\n"), text);
        assertTrue(text.endsWith("# <<< weblogic-trace <<<\n"), text);
        assertFalse(text.contains("\r"), "shell files get LF");
        assertTrue(OverridesBlock.isPresent(file));
        assertTrue(unixBlock.remove(file));
        assertFalse(Files.exists(file), "enable created it, disable deletes it");
    }

    @Test
    void leavesAnExistingShellFileByteForByte() throws Exception {
        for (String original : new String[]{"# mine\nJAVA_OPTIONS=\"$JAVA_OPTIONS -Dx=1\"\n", "# no trailing newline\nexport Y=2"}) {
            Path file = tmp.resolve("setUserOverrides.sh");
            Files.write(file, original.getBytes(StandardCharsets.UTF_8));
            assertFalse(unixBlock.install(file, jar, out, 8));
            String enabled = read(file);
            assertTrue(enabled.startsWith(original.endsWith("\n") ? original : original + "\n"), enabled);
            assertFalse(enabled.contains("--add-opens"), "JDK 8: no add-opens");
            assertFalse(unixBlock.install(file, jar, out, 11));
            String updated = read(file);
            assertEquals(1, count(updated, "# >>> weblogic-trace >>>"), "a second enable updates in place");
            assertTrue(updated.contains("--add-opens"), "JDK 11: add-opens");
            assertTrue(unixBlock.remove(file));
            assertEquals(original, read(file), "only the block and its separator are removed");
            Files.delete(file);
        }
    }

    @Test
    void createsTheCmdFileWhenAbsentAndRemovesItAgain() throws Exception {
        Path file = tmp.resolve("bin").resolve("setUserOverrides.cmd");
        assertTrue(windowsBlock.install(file, windowsJar, windowsOut, 21));
        String text = read(file);
        assertTrue(text.startsWith("rem >>> weblogic-trace >>>\r\n"), text);
        assertTrue(text.contains("rem weblogic-trace created this file; wltrace:disable removes it\r\n"), text);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% -javaagent:" + windowsJar + "=out=" + windowsOut + "\r\n"), text);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% --add-opens=java.base/java.lang=ALL-UNNAMED\r\n"), text);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% --add-opens=java.logging/java.util.logging=ALL-UNNAMED\r\n"), text);
        assertFalse(text.contains("export"), "no shell syntax in a cmd file");
        assertFalse(text.contains("#"), text);
        assertTrue(text.endsWith("rem <<< weblogic-trace <<<\r\n"), text);
        assertEquals(count(text, "\n"), count(text, "\r\n"), "every line ends in CRLF");
        assertTrue(OverridesBlock.isPresent(file));
        assertTrue(windowsBlock.remove(file));
        assertFalse(Files.exists(file), "enable created it, disable deletes it");
    }

    @Test
    void leavesAnExistingCmdFileByteForByte() throws Exception {
        for (String original : new String[]{"@echo off\r\nset JAVA_OPTIONS=%JAVA_OPTIONS% -Dx=1\r\n", "rem no trailing newline\r\nset Y=2"}) {
            Path file = tmp.resolve("setUserOverrides.cmd");
            Files.write(file, original.getBytes(StandardCharsets.UTF_8));
            assertFalse(windowsBlock.install(file, windowsJar, windowsOut, 8));
            String enabled = read(file);
            assertTrue(enabled.startsWith(original.endsWith("\n") ? original : original + "\r\n"), enabled);
            assertTrue(enabled.contains("\r\nrem >>> weblogic-trace >>>\r\n"), enabled);
            assertFalse(enabled.contains("--add-opens"), "JDK 8: no add-opens");
            assertFalse(windowsBlock.install(file, windowsJar, windowsOut, 21));
            String updated = read(file);
            assertEquals(1, count(updated, "rem >>> weblogic-trace >>>"), "a second enable updates in place");
            assertTrue(updated.contains("--add-opens"), "JDK 21: add-opens");
            assertTrue(windowsBlock.remove(file));
            assertEquals(original, read(file), "only the block and its separator are removed");
            Files.delete(file);
        }
    }

    @Test
    void cmdQuotesAnOptionWithSpacesOrSpecialCharacters() {
        Path spaced = Paths.get("C:\\Program Files\\Oracle\\domain\\weblogic-trace\\weblogic-trace-agent.jar");
        String text = windowsBlock.text(spaced, windowsOut, 8, false, false);
        assertTrue(text.contains("set JAVA_OPTIONS=%JAVA_OPTIONS% \"-javaagent:" + spaced + "=out=" + windowsOut + "\"\r\n"), text);
        assertEquals("--add-opens=java.base/java.lang=ALL-UNNAMED", WindowsPlatform.quote("--add-opens=java.base/java.lang=ALL-UNNAMED"));
        assertEquals("\"a&b\"", WindowsPlatform.quote("a&b"));
    }

    @Test
    void javaOptionsLineForStartupProperties() {
        String line = OverridesBlock.javaOptions(jar, out, 21);
        assertTrue(line.startsWith("-javaagent:" + jar + "=out=" + out + " --add-opens="), line);
        assertEquals("-javaagent:" + jar + "=out=" + out, OverridesBlock.javaOptions(jar, out, 8));
        assertEquals("-javaagent:C:\\\\Oracle\\\\domains\\\\base_domain\\\\weblogic-trace\\\\weblogic-trace-agent.jar=out=C:\\\\Users\\\\me\\\\.weblogic-trace\\\\base_domain",
                NodeManagerSettings.propertiesValue(OverridesBlock.javaOptions(windowsJar, windowsOut, 8)), "backslashes doubled for a .properties file");
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int occurrences = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            occurrences++;
        }
        return occurrences;
    }
}
