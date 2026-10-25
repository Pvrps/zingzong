package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import java.util.Set;

/**
 * Non-walkable runtime handles. These are the only objects the serializer does not walk
 * field by field; each becomes a typed reference with identity and toString
 * ({@code @handle}). The test for inclusion is "walking its fields describes the JVM or the
 * OS, not the payload", never "sensitive" or "uninteresting". Everything else, including
 * loggers, JPA and framework objects and container classes, is walked like any other object.
 * Matched against every class and interface in the object's hierarchy, by name.
 */
final class InfrastructureTypes {

    private static final String[][] HANDLES = {
            // threads: a running execution context, its fields are stack and scheduler state
            {"java.lang.Thread", "thread"},
            {"java.lang.ThreadGroup", "thread group"},
            // class loaders and classes: JVM metadata, not application data
            {"java.lang.ClassLoader", "class loader"},
            {"java.lang.Class", "class reference"},
            // OS processes
            {"java.lang.Process", "OS process"},
            {"java.lang.ProcessHandle", "OS process handle"},
            // sockets and streams: a cursor into an OS resource; reading would consume it
            {"java.net.Socket", "socket"},
            {"java.net.ServerSocket", "socket"},
            {"java.net.DatagramSocket", "socket"},
            {"java.net.URLConnection", "connection"},
            {"java.io.InputStream", "stream"},
            {"java.io.OutputStream", "stream"},
            {"java.io.Reader", "stream"},
            {"java.io.Writer", "stream"},
            {"java.io.RandomAccessFile", "file handle"},
            {"java.io.Console", "console"},
            {"java.nio.channels.Channel", "channel"},
            // JDBC connections and statements: live database cursors
            {"java.sql.Connection", "JDBC connection"},
            {"java.sql.Statement", "JDBC statement"},
            {"java.sql.ResultSet", "JDBC result set"},
            {"javax.sql.DataSource", "JDBC connection factory"},
            // locks and executors: synchroniser and scheduler state
            {"java.util.concurrent.locks.Lock", "lock"},
            {"java.util.concurrent.locks.ReadWriteLock", "lock"},
            {"java.util.concurrent.locks.Condition", "lock condition"},
            {"java.util.concurrent.Semaphore", "semaphore"},
            {"java.util.concurrent.CountDownLatch", "latch"},
            {"java.util.concurrent.CyclicBarrier", "barrier"},
            {"java.util.concurrent.Phaser", "phaser"},
            {"java.util.concurrent.Executor", "executor"},
            {"java.util.concurrent.ExecutorService", "executor"},
            {"java.util.concurrent.Future", "future"},
            {"java.util.concurrent.CompletionStage", "future"},
            {"java.util.Timer", "timer"},
    };

    private InfrastructureTypes() {
    }

    /** The handle category, or null when the object is walkable. */
    static String handleKind(Set<Class<?>> hierarchy) {
        for (Class<?> c : hierarchy) {
            String n = c.getName();
            for (String[] handle : HANDLES) {
                if (n.equals(handle[0])) {
                    return handle[1];
                }
            }
        }
        return null;
    }
}
