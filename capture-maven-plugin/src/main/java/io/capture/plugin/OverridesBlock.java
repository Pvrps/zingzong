package io.capture.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The delimited block {@code capture:enable} appends to {@code bin/setUserOverrides.sh}. Only
 * the text between the markers (plus the separator the plugin itself added) is ever written
 * or removed; everything else in the file stays byte for byte, down to a missing trailing
 * newline. The block records whether the file existed before, so {@code disable} knows
 * whether to delete it.
 */
public final class OverridesBlock {

    public static final String BEGIN = "# >>> ejb-capture >>>";
    public static final String END = "# <<< ejb-capture <<<";
    static final String CREATED_FILE = "# ejb-capture created this file; capture:disable removes it";
    static final String NO_TRAILING_NEWLINE = "# ejb-capture: the file ended without a newline; capture:disable restores that";

    /** What the serializer needs opened on JDK 9+; the same list the lab's start script used. */
    public static final String[] ADD_OPENS = {
            "java.base/java.lang=ALL-UNNAMED",
            "java.base/java.util=ALL-UNNAMED",
            "java.base/java.time=ALL-UNNAMED",
            "java.sql/java.sql=ALL-UNNAMED",
            "java.logging/java.util.logging=ALL-UNNAMED",
    };

    private OverridesBlock() {
    }

    /** The block text, newline-terminated. */
    public static String text(File agentJar, File outDir, int domainJavaMajor, boolean createdFile, boolean noTrailingNewline) {
        List<String> lines = new ArrayList<String>();
        lines.add(BEGIN);
        if (createdFile) {
            lines.add(CREATED_FILE);
        }
        if (noTrailingNewline) {
            lines.add(NO_TRAILING_NEWLINE);
        }
        lines.add("# Added by capture:enable (io.capture:capture-maven-plugin). Removed by capture:disable.");
        lines.add("JAVA_OPTIONS=\"${JAVA_OPTIONS} -javaagent:" + agentJar.getPath() + "=out=" + outDir.getPath() + "\"");
        if (domainJavaMajor >= 9) {
            lines.add("# JDK 9+: reflective access the serializer needs to read JDK value types as fields.");
            for (String open : ADD_OPENS) {
                lines.add("JAVA_OPTIONS=\"${JAVA_OPTIONS} --add-opens=" + open + "\"");
            }
        }
        lines.add("export JAVA_OPTIONS");
        lines.add(END);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** The JAVA_OPTIONS value the block sets, for startup.properties when NodeManager skips start scripts. */
    public static String javaOptions(File agentJar, File outDir, int domainJavaMajor) {
        StringBuilder sb = new StringBuilder("-javaagent:" + agentJar.getPath() + "=out=" + outDir.getPath());
        if (domainJavaMajor >= 9) {
            for (String open : ADD_OPENS) {
                sb.append(" --add-opens=").append(open);
            }
        }
        return sb.toString();
    }

    /** The current block of a file (BEGIN line through END line, newline-terminated), or null. */
    public static String read(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        String all = readText(file);
        int[] span = span(all);
        return span == null ? null : all.substring(span[0], span[1]);
    }

    public static boolean isPresent(File file) throws IOException {
        return read(file) != null;
    }

    /** Writes or replaces the block. Returns true when the file was created. */
    public static boolean install(File file, File agentJar, File outDir, int domainJavaMajor) throws IOException {
        boolean existed = file.isFile();
        String all = existed ? readText(file) : "";
        int[] span = span(all);
        String result;
        if (span != null) {
            String old = all.substring(span[0], span[1]);
            String block = text(agentJar, outDir, domainJavaMajor, old.contains(CREATED_FILE + "\n"), old.contains(NO_TRAILING_NEWLINE + "\n"));
            result = all.substring(0, span[0]) + block + all.substring(span[1]);
        } else if (!existed) {
            result = "#!/bin/sh\n" + text(agentJar, outDir, domainJavaMajor, true, false);
        } else {
            boolean noNewline = !all.isEmpty() && !all.endsWith("\n");
            result = all + (noNewline ? "\n" : "") + "\n" + text(agentJar, outDir, domainJavaMajor, false, noNewline);
        }
        writeText(file, result);
        if (!existed) {
            file.setExecutable(true, false);
        }
        return !existed;
    }

    /** Removes the block (and the separator enable added, or the whole file when enable created it). Returns true when something was removed. */
    public static boolean remove(File file) throws IOException {
        if (!file.isFile()) {
            return false;
        }
        String all = readText(file);
        int[] span = span(all);
        if (span == null) {
            return false;
        }
        String block = all.substring(span[0], span[1]);
        String before = all.substring(0, span[0]);
        String after = all.substring(span[1]);
        if (block.contains(CREATED_FILE + "\n") && after.trim().isEmpty() && before.trim().equals("#!/bin/sh")) {
            Files.delete(file.toPath());
            return true;
        }
        if (before.endsWith("\n\n")) {
            before = before.substring(0, before.length() - 1);
        }
        if (block.contains(NO_TRAILING_NEWLINE + "\n") && after.isEmpty() && before.endsWith("\n")) {
            before = before.substring(0, before.length() - 1);
        }
        writeText(file, before + after);
        return true;
    }

    /** [start of the BEGIN line, end of the END line including its newline], or null. */
    private static int[] span(String all) {
        int begin = indexOfLine(all, BEGIN);
        if (begin < 0) {
            return null;
        }
        int end = indexOfLine(all, END);
        if (end < begin) {
            return null;
        }
        int endOfLine = all.indexOf('\n', end);
        return new int[]{begin, endOfLine < 0 ? all.length() : endOfLine + 1};
    }

    private static int indexOfLine(String all, String line) {
        if (all.startsWith(line) && (all.length() == line.length() || all.charAt(line.length()) == '\n')) {
            return 0;
        }
        int i = all.indexOf("\n" + line);
        while (i >= 0) {
            int after = i + 1 + line.length();
            if (after == all.length() || all.charAt(after) == '\n') {
                return i + 1;
            }
            i = all.indexOf("\n" + line, i + 1);
        }
        return -1;
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, file.getName() + ".ejb-capture.tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (file.isFile()) {
            try {
                Files.setPosixFilePermissions(tmp.toPath(), Files.getPosixFilePermissions(file.toPath()));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
