package ca.gc.cra.xz02.devops.wltrace.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The delimited block {@code wltrace:enable} writes into the domain's overrides file:
 * {@code bin/setUserOverrides.sh} with shell syntax, {@code bin\setUserOverrides.cmd} with cmd
 * syntax. Only the text between the markers, plus the separator the plugin itself added, is ever
 * written or removed; everything else in the file stays byte for byte, line endings and a
 * missing trailing newline included. The block records whether the file existed before, so
 * {@code disable} knows whether to delete it.
 */
public final class OverridesBlock {

    /** The two file syntaxes, chosen by the domain's platform. */
    public enum Syntax {
        SH("# ", "#!/bin/sh", "\n"),
        CMD("rem ", "", "\r\n");

        final String comment;
        /** The first line of a file the plugin creates; empty when none. */
        final String header;
        final String eol;
        final String begin;
        final String end;

        Syntax(String comment, String header, String eol) {
            this.comment = comment;
            this.header = header;
            this.eol = eol;
            this.begin = comment + ">>> weblogic-trace >>>";
            this.end = comment + "<<< weblogic-trace <<<";
        }

        public static Syntax of(Domain.Platform platform) {
            return platform == Domain.Platform.WINDOWS ? CMD : SH;
        }

        String createdFile() {
            return comment + "weblogic-trace created this file; wltrace:disable removes it";
        }

        String noTrailingNewline() {
            return comment + "weblogic-trace: the file ended without a newline; wltrace:disable restores that";
        }

        /** One line that appends an option to JAVA_OPTIONS. */
        String append(String option) {
            return this == CMD
                    ? "set JAVA_OPTIONS=%JAVA_OPTIONS% " + quoteForCmd(option)
                    : "JAVA_OPTIONS=\"${JAVA_OPTIONS} " + option + "\"";
        }
    }

    /** What the serializer needs opened on JDK 9+. */
    public static final String[] ADD_OPENS = {
            "java.base/java.lang=ALL-UNNAMED",
            "java.base/java.util=ALL-UNNAMED",
            "java.base/java.time=ALL-UNNAMED",
            "java.sql/java.sql=ALL-UNNAMED",
            "java.logging/java.util.logging=ALL-UNNAMED",
    };

    private OverridesBlock() {
    }

    /** The block text, terminated by the syntax's line ending. */
    public static String text(Syntax syntax, Path agentJar, Path outDir, int domainJavaMajor, boolean createdFile, boolean noTrailingNewline) {
        List<String> lines = new ArrayList<String>();
        lines.add(syntax.begin);
        if (createdFile) {
            lines.add(syntax.createdFile());
        }
        if (noTrailingNewline) {
            lines.add(syntax.noTrailingNewline());
        }
        lines.add(syntax.comment + "Added by wltrace:enable (ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin). Removed by wltrace:disable.");
        lines.add(syntax.append(javaAgentOption(agentJar, outDir)));
        if (domainJavaMajor >= 9) {
            lines.add(syntax.comment + "JDK 9+: reflective access the serializer needs to read JDK value types as fields.");
            for (String open : ADD_OPENS) {
                lines.add(syntax.append("--add-opens=" + open));
            }
        }
        if (syntax == Syntax.SH) {
            lines.add("export JAVA_OPTIONS");
        }
        lines.add(syntax.end);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(syntax.eol);
        }
        return sb.toString();
    }

    /** The JAVA_OPTIONS value the block sets, for startup.properties when Node Manager skips start scripts. */
    public static String javaOptions(Path agentJar, Path outDir, int domainJavaMajor) {
        StringBuilder sb = new StringBuilder(javaAgentOption(agentJar, outDir));
        if (domainJavaMajor >= 9) {
            for (String open : ADD_OPENS) {
                sb.append(" --add-opens=").append(open);
            }
        }
        return sb.toString();
    }

    static String javaAgentOption(Path agentJar, Path outDir) {
        return "-javaagent:" + agentJar + "=out=" + outDir;
    }

    /** Wraps a cmd {@code set} value fragment in double quotes when it has a space or a character cmd would interpret. */
    static String quoteForCmd(String option) {
        for (char c : option.toCharArray()) {
            if (c == ' ' || "&|<>^()".indexOf(c) >= 0) {
                return "\"" + option + "\"";
            }
        }
        return option;
    }

    /** The current block of a file, BEGIN line through END line with its line ending, or null. */
    public static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String all = readText(file);
        int[] span = span(all);
        return span == null ? null : all.substring(span[0], span[1]);
    }

    public static boolean isPresent(Path file) throws IOException {
        return read(file) != null;
    }

    /** Writes or replaces the block. Returns true when the file was created. */
    public static boolean install(Path file, Syntax syntax, Path agentJar, Path outDir, int domainJavaMajor) throws IOException {
        boolean existed = Files.isRegularFile(file);
        String all = existed ? readText(file) : "";
        int[] span = span(all);
        String result;
        if (span != null) {
            String old = all.substring(span[0], span[1]);
            result = all.substring(0, span[0])
                    + text(syntax, agentJar, outDir, domainJavaMajor, old.contains(syntax.createdFile()), old.contains(syntax.noTrailingNewline()))
                    + all.substring(span[1]);
        } else if (!existed) {
            result = (syntax.header.isEmpty() ? "" : syntax.header + syntax.eol)
                    + text(syntax, agentJar, outDir, domainJavaMajor, true, false);
        } else {
            boolean noNewline = !all.isEmpty() && !all.endsWith("\n");
            result = all + (noNewline ? syntax.eol : "") + syntax.eol + text(syntax, agentJar, outDir, domainJavaMajor, false, noNewline);
        }
        writeText(file, result);
        if (!existed && syntax == Syntax.SH) {
            file.toFile().setExecutable(true, false);
        }
        return !existed;
    }

    /** Removes the block and the separator enable added, or the whole file when enable created it. Returns true when something was removed. */
    public static boolean remove(Path file, Syntax syntax) throws IOException {
        if (!Files.isRegularFile(file)) {
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
        if (block.contains(syntax.createdFile()) && after.trim().isEmpty() && before.trim().equals(syntax.header)) {
            Files.delete(file);
            return true;
        }
        if (before.endsWith(syntax.eol + syntax.eol)) {
            before = before.substring(0, before.length() - syntax.eol.length());
        }
        if (block.contains(syntax.noTrailingNewline()) && after.isEmpty() && before.endsWith(syntax.eol)) {
            before = before.substring(0, before.length() - syntax.eol.length());
        }
        writeText(file, before + after);
        return true;
    }

    /** [start of the BEGIN line, end of the END line including its line ending], for either syntax, or null. */
    private static int[] span(String all) {
        for (Syntax syntax : Syntax.values()) {
            int begin = indexOfLine(all, syntax.begin);
            if (begin < 0) {
                continue;
            }
            int end = indexOfLine(all, syntax.end);
            if (end < begin) {
                return null;
            }
            int endOfLine = all.indexOf('\n', end);
            return new int[]{begin, endOfLine < 0 ? all.length() : endOfLine + 1};
        }
        return null;
    }

    /** The index of a line that is exactly {@code line}, with or without a carriage return, or -1. */
    private static int indexOfLine(String all, String line) {
        int from = 0;
        while (from <= all.length()) {
            int i = all.indexOf(line, from);
            if (i < 0) {
                return -1;
            }
            boolean atLineStart = i == 0 || all.charAt(i - 1) == '\n';
            int after = i + line.length();
            boolean atLineEnd = after == all.length() || all.charAt(after) == '\n'
                    || (all.charAt(after) == '\r' && after + 1 < all.length() && all.charAt(after + 1) == '\n');
            if (atLineStart && atLineEnd) {
                return i;
            }
            from = i + 1;
        }
        return -1;
    }

    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void writeText(Path file, String text) throws IOException {
        Path dir = file.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".weblogic-trace.tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        if (Files.isRegularFile(file)) {
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
