package ca.gc.cra.xz02.devops.wltrace.plugin.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The delimited block {@code wltrace:enable} writes into the domain's overrides file, in the
 * platform's syntax. Only the text between the markers, plus the separator the plugin itself
 * added, is ever written or removed; everything else in the file stays byte for byte, line
 * endings and a missing trailing newline included. The block records whether the file existed
 * before, so {@code wltrace:disable} knows whether to delete it.
 */
public final class OverridesBlock {

    /** The reflective access the serializer needs on JDK 9 and later. */
    public static final List<String> ADD_OPENS = Arrays.asList(
            "java.base/java.lang=ALL-UNNAMED",
            "java.base/java.util=ALL-UNNAMED",
            "java.base/java.time=ALL-UNNAMED",
            "java.sql/java.sql=ALL-UNNAMED",
            "java.logging/java.util.logging=ALL-UNNAMED");

    private static final String BEGIN = ">>> weblogic-trace >>>";
    private static final String END = "<<< weblogic-trace <<<";
    private static final String CREATED_FILE = "weblogic-trace created this file; wltrace:disable removes it";
    private static final String NO_TRAILING_NEWLINE = "weblogic-trace: the file ended without a newline; wltrace:disable restores that";
    private static final String ADDED_BY = "Added by wltrace:enable (ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin). Removed by wltrace:disable.";
    private static final String ADD_OPENS_NOTE = "JDK 9+: reflective access the serializer needs to read JDK value types as fields.";

    private final DomainPlatform platform;

    public OverridesBlock(DomainPlatform platform) {
        this.platform = platform;
    }

    /** The {@code -javaagent} option for the installed jar and output directory. */
    public static String javaAgentOption(Path agentJar, Path outputDirectory) {
        return "-javaagent:" + agentJar + "=out=" + outputDirectory;
    }

    /** The {@code JAVA_OPTIONS} value the block sets, as one line for {@code startup.properties} when Node Manager skips start scripts. */
    public static String javaOptions(Path agentJar, Path outputDirectory, int domainJavaMajor) {
        StringBuilder options = new StringBuilder(javaAgentOption(agentJar, outputDirectory));
        if (domainJavaMajor >= DomainJdk.FIRST_MODULAR_MAJOR) {
            ADD_OPENS.forEach(open -> options.append(" --add-opens=").append(open));
        }
        return options.toString();
    }

    /** The block text, every line ending in the platform's line ending. */
    public String text(Path agentJar, Path outputDirectory, int domainJavaMajor, boolean createdFile, boolean noTrailingNewline) {
        List<String> lines = new ArrayList<>();
        lines.add(comment(BEGIN));
        if (createdFile) {
            lines.add(comment(CREATED_FILE));
        }
        if (noTrailingNewline) {
            lines.add(comment(NO_TRAILING_NEWLINE));
        }
        lines.add(comment(ADDED_BY));
        lines.add(platform.appendToJavaOptions(javaAgentOption(agentJar, outputDirectory)));
        if (domainJavaMajor >= DomainJdk.FIRST_MODULAR_MAJOR) {
            lines.add(comment(ADD_OPENS_NOTE));
            ADD_OPENS.forEach(open -> lines.add(platform.appendToJavaOptions("--add-opens=" + open)));
        }
        lines.addAll(platform.blockTrailer());
        lines.add(comment(END));
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append(platform.lineEnding()));
        return text.toString();
    }

    /** The current block of the file, BEGIN line through END line with its line ending, in either platform's syntax. */
    public static Optional<String> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String all = readText(file);
        return span(all).map(span -> all.substring(span[0], span[1]));
    }

    public static boolean isPresent(Path file) throws IOException {
        return read(file).isPresent();
    }

    /** Writes or replaces the block. Returns true when the file was created. */
    public boolean install(Path file, Path agentJar, Path outputDirectory, int domainJavaMajor) throws IOException {
        boolean existed = Files.isRegularFile(file);
        String all = existed ? readText(file) : "";
        Optional<int[]> span = span(all);
        String result;
        if (span.isPresent()) {
            String old = all.substring(span.get()[0], span.get()[1]);
            result = all.substring(0, span.get()[0])
                    + text(agentJar, outputDirectory, domainJavaMajor, old.contains(comment(CREATED_FILE)), old.contains(comment(NO_TRAILING_NEWLINE)))
                    + all.substring(span.get()[1]);
        } else if (!existed) {
            String header = platform.createdFileHeader();
            result = (header.isEmpty() ? "" : header + platform.lineEnding())
                    + text(agentJar, outputDirectory, domainJavaMajor, true, false);
        } else {
            boolean noNewline = !all.isEmpty() && !all.endsWith("\n");
            result = all + (noNewline ? platform.lineEnding() : "") + platform.lineEnding()
                    + text(agentJar, outputDirectory, domainJavaMajor, false, noNewline);
        }
        writeText(file, result);
        if (!existed && platform.overridesFileExecutable()) {
            file.toFile().setExecutable(true, false);
        }
        return !existed;
    }

    /** Removes the block and the separator enable added, or the whole file when enable created it. Returns true when something was removed. */
    public boolean remove(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        String all = readText(file);
        Optional<int[]> span = span(all);
        if (!span.isPresent()) {
            return false;
        }
        String block = all.substring(span.get()[0], span.get()[1]);
        String before = all.substring(0, span.get()[0]);
        String after = all.substring(span.get()[1]);
        if (block.contains(comment(CREATED_FILE)) && after.trim().isEmpty() && before.trim().equals(platform.createdFileHeader())) {
            Files.delete(file);
            return true;
        }
        String lineEnding = platform.lineEnding();
        if (before.endsWith(lineEnding + lineEnding)) {
            before = before.substring(0, before.length() - lineEnding.length());
        }
        if (block.contains(comment(NO_TRAILING_NEWLINE)) && after.isEmpty() && before.endsWith(lineEnding)) {
            before = before.substring(0, before.length() - lineEnding.length());
        }
        writeText(file, before + after);
        return true;
    }

    private String comment(String text) {
        return platform.commentPrefix() + text;
    }

    /** [start of the BEGIN line, end of the END line including its line ending] for either platform's syntax, or empty. */
    private static Optional<int[]> span(String all) {
        for (DomainPlatform platform : new DomainPlatform[]{new UnixPlatform(), new WindowsPlatform()}) {
            int begin = indexOfLine(all, platform.commentPrefix() + BEGIN);
            if (begin < 0) {
                continue;
            }
            int end = indexOfLine(all, platform.commentPrefix() + END);
            if (end < begin) {
                return Optional.empty();
            }
            int endOfLine = all.indexOf('\n', end);
            return Optional.of(new int[]{begin, endOfLine < 0 ? all.length() : endOfLine + 1});
        }
        return Optional.empty();
    }

    /** The index of a line that is exactly {@code line}, with or without a carriage return, or -1. */
    private static int indexOfLine(String all, String line) {
        int from = 0;
        while (from <= all.length()) {
            int index = all.indexOf(line, from);
            if (index < 0) {
                return -1;
            }
            boolean atLineStart = index == 0 || all.charAt(index - 1) == '\n';
            int after = index + line.length();
            boolean atLineEnd = after == all.length() || all.charAt(after) == '\n'
                    || (all.charAt(after) == '\r' && after + 1 < all.length() && all.charAt(after + 1) == '\n');
            if (atLineStart && atLineEnd) {
                return index;
            }
            from = index + 1;
        }
        return -1;
    }

    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void writeText(Path file, String text) throws IOException {
        Path directory = file.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".weblogic-trace.tmp");
        Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
        if (Files.isRegularFile(file)) {
            try {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
