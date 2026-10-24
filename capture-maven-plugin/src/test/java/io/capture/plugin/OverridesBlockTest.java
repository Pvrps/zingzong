package io.capture.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverridesBlockTest {

    @TempDir
    Path tmp;

    private final File jar = new File("/domain/ejb-capture/capture-agent.jar");
    private final File out = new File("/home/me/.ejb-capture/domain");

    @Test
    void createsTheFileWhenAbsentAndRemovesItAgain() throws Exception {
        File f = tmp.resolve("bin/setUserOverrides.sh").toFile();
        assertTrue(OverridesBlock.install(f, jar, out, 21));
        String text = read(f);
        assertTrue(text.startsWith("#!/bin/sh\n" + OverridesBlock.BEGIN + "\n"), text);
        assertTrue(text.contains("-javaagent:" + jar.getPath() + "=out=" + out.getPath()), text);
        assertTrue(text.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), text);
        assertTrue(text.endsWith(OverridesBlock.END + "\n"), text);
        assertTrue(OverridesBlock.isPresent(f));
        assertTrue(OverridesBlock.remove(f));
        assertFalse(f.exists(), "enable created it, disable deletes it");
    }

    @Test
    void leavesAnExistingFileByteForByte() throws Exception {
        for (String original : new String[]{"# mine\nJAVA_OPTIONS=\"$JAVA_OPTIONS -Dx=1\"\n", "# no trailing newline\nexport Y=2"}) {
            File f = tmp.resolve("setUserOverrides.sh").toFile();
            Files.write(f.toPath(), original.getBytes(StandardCharsets.UTF_8));
            assertFalse(OverridesBlock.install(f, jar, out, 8));
            String enabled = read(f);
            assertTrue(enabled.startsWith(original.endsWith("\n") ? original : original + "\n"), enabled);
            assertFalse(enabled.contains("--add-opens"), "JDK 8: no add-opens");
            // a second enable updates in place: still exactly one block
            assertFalse(OverridesBlock.install(f, jar, out, 11));
            String updated = read(f);
            assertEquals(1, count(updated, OverridesBlock.BEGIN));
            assertTrue(updated.contains("--add-opens"), "JDK 11: add-opens");
            assertTrue(OverridesBlock.remove(f));
            assertEquals(original, read(f), "only the block and its separator are removed");
            Files.delete(f.toPath());
        }
    }

    @Test
    void javaOptionsLineForStartupProperties() {
        String line = OverridesBlock.javaOptions(jar, out, 21);
        assertTrue(line.startsWith("-javaagent:" + jar.getPath() + "=out=" + out.getPath() + " --add-opens="), line);
        assertEquals("-javaagent:" + jar.getPath() + "=out=" + out.getPath(), OverridesBlock.javaOptions(jar, out, 8));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
