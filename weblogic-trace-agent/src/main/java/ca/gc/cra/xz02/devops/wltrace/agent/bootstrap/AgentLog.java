package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * The agent's own log file, {@code <out>/agent.log}. Never stdout: the server owns that.
 * Falls back to stderr only if the file cannot be opened. Never throws.
 */
public final class AgentLog {

    private static final Object LOCK = new Object();
    private static PrintWriter writer;
    private static File file;
    private static final SimpleDateFormat FORMAT = utcFormat();

    private AgentLog() {
    }

    public static void init(File outDir) {
        synchronized (LOCK) {
            close();
            try {
                if (!outDir.isDirectory() && !outDir.mkdirs()) {
                    throw new IOException("cannot create " + outDir);
                }
                file = new File(outDir, "agent.log");
                writer = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(file, true), Charset.forName("UTF-8")), true);
            } catch (Throwable t) {
                writer = null;
                file = null;
                System.err.println("[weblogic-trace-agent] cannot open agent.log under " + outDir + ": " + t);
            }
        }
    }

    public static File file() {
        synchronized (LOCK) {
            return file;
        }
    }

    public static void info(String message) {
        write("INFO ", message, null);
    }

    public static void warn(String message) {
        write("WARN ", message, null);
    }

    public static void error(String message, Throwable t) {
        write("ERROR", message, t);
    }

    /** An event line such as ENTER/EXIT; same stream, no level. */
    public static void event(String message) {
        write("EVENT", message, null);
    }

    private static void write(String level, String message, Throwable t) {
        try {
            String line;
            synchronized (FORMAT) {
                line = FORMAT.format(new Date()) + " " + level + " " + message;
            }
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                line = line + System.lineSeparator() + sw;
            }
            synchronized (LOCK) {
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

    private static void close() {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private static SimpleDateFormat utcFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }
}
