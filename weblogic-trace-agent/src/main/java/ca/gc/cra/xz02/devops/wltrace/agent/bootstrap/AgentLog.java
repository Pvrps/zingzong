package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The agent's own log file, {@code <out>/agent.log}. Never stdout: the server owns that. Falls back
 * to stderr when the file cannot be opened. Never throws. {@link #warnOnce} keeps a degraded
 * lookup from flooding the log: one line per key for the life of the agent.
 */
public final class AgentLog {

    private final Object lock = new Object();
    private final SimpleDateFormat timestamps = utcTimestamps();
    private final Set<String> warnedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final PrintWriter writer;
    private final File file;

    private AgentLog(PrintWriter writer, File file) {
        this.writer = writer;
        this.file = file;
    }

    /** Opens {@code agent.log} under the directory, creating the directory; stderr when that fails. */
    public static AgentLog open(File outputDirectory) {
        try {
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                throw new IOException("cannot create " + outputDirectory);
            }
            File file = new File(outputDirectory, "agent.log");
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8), true);
            return new AgentLog(writer, file);
        } catch (Throwable failure) {
            System.err.println("[weblogic-trace-agent] cannot open agent.log under " + outputDirectory + ": " + failure);
            return new AgentLog(null, null);
        }
    }

    /** The log file, or null when the log writes to stderr. */
    public File file() {
        return file;
    }

    public void info(String message) {
        write("INFO ", message, null);
    }

    public void warn(String message) {
        write("WARN ", message, null);
    }

    /** Warns the first time a key is seen and stays silent for it afterwards. */
    public void warnOnce(String key, String message) {
        if (warnedKeys.add(key)) {
            warn(message);
        }
    }

    public void error(String message, Throwable failure) {
        write("ERROR", message, failure);
    }

    /** A per-trace event line, same stream, no level. */
    public void event(String message) {
        write("EVENT", message, null);
    }

    private void write(String level, String message, Throwable failure) {
        try {
            String line;
            synchronized (timestamps) {
                line = timestamps.format(new Date()) + " " + level + " " + message;
            }
            if (failure != null) {
                StringWriter stack = new StringWriter();
                failure.printStackTrace(new PrintWriter(stack));
                line = line + System.lineSeparator() + stack;
            }
            synchronized (lock) {
                if (writer != null) {
                    writer.println(line);
                } else {
                    System.err.println("[weblogic-trace-agent] " + line);
                }
            }
        } catch (Throwable ignored) {
            // logging must never break the caller
        }
    }

    private static SimpleDateFormat utcTimestamps() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }
}
