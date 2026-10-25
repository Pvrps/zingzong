package io.capture.agent.span;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.capture.agent.meta.ContainerInvocation;
import io.capture.agent.meta.WlsDataSources;
import io.capture.trace.SerializationWarning;
import io.capture.trace.Span;
import io.capture.trace.SqlInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * SQL through WebLogic's JDBC wrappers, called from {@code JdbcAdvice}: the parameters bound on a
 * prepared statement, one span per {@code execute*} with the statement, the parameters and what
 * came back, and the rows the application stepped through afterwards.
 */
public final class JdbcRecorder {

    /** The parameters bound on a prepared statement so far, and the batches added. */
    private static final class Binds {
        final TreeMap<Integer, Object> current = new TreeMap<Integer, Object>();
        final List<TreeMap<Integer, Object>> batches = new ArrayList<TreeMap<Integer, Object>>();
        final List<String> sqlBatch = new ArrayList<String>();
    }

    private static final Map<Object, Binds> BINDS = Collections.synchronizedMap(new WeakHashMap<Object, Binds>());
    /** The SQL record of each statement's last execution that produced a result set, while its rows are being read. */
    private static final Map<Object, SqlInfo> OPEN_RESULTS = Collections.synchronizedMap(new WeakHashMap<Object, SqlInfo>());

    private JdbcRecorder() {
    }

    /** A prepared statement's {@code set*(index, value)}, {@code setNull}, {@code addBatch} or {@code clearParameters}. */
    public static void bind(Object statement, String method, Object[] args) {
        if (Spans.active() == null) {
            return;
        }
        try {
            Binds binds = BINDS.get(statement);
            if (binds == null) {
                binds = new Binds();
                BINDS.put(statement, binds);
            }
            synchronized (binds) {
                if (method.equals("clearParameters")) {
                    binds.current.clear();
                } else if (method.equals("addBatch")) {
                    if (args.length == 1 && args[0] instanceof String) {
                        binds.sqlBatch.add((String) args[0]);
                    } else {
                        binds.batches.add(new TreeMap<Integer, Object>(binds.current));
                    }
                } else if (args.length >= 1 && args[0] instanceof Integer) {
                    binds.current.put((Integer) args[0], method.equals("setNull") || args.length < 2 ? null : args[1]);
                }
            }
        } catch (Throwable ignored) {
            // never into the statement
        }
    }

    /** A statement's {@code execute*}: opens the SQL span with what the wrapper knows before the driver runs. */
    public static long enter(Object statement, String operation, Object[] args) {
        Recording recording = Spans.active();
        if (recording == null) {
            return 0L;
        }
        try {
            Object conn = ContainerInvocation.fieldUp(statement, "conn");
            Object pool = conn == null ? null : ContainerInvocation.call(conn, "getPoolName");
            String poolName = pool == null ? "?" : pool.toString();
            SqlInfo info = new SqlInfo();
            info.dataSource = poolName;
            WlsDataSources.DataSource ds = WlsDataSources.describe(poolName);
            if (ds != null) {
                info.jndiNames = ds.jndiNames.isEmpty() ? null : ds.jndiNames;
                info.url = ds.url;
                info.database = ds.database;
            }
            info.statementType = statementType(statement);
            info.operation = operation;
            Object sql = ContainerInvocation.fieldUp(statement, "sql");
            info.sql = sql != null ? sql.toString() : args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
            List<SerializationWarning> warnings = new ArrayList<SerializationWarning>();
            Binds binds = BINDS.get(statement);
            if (binds != null) {
                synchronized (binds) {
                    if (operation.equals("executeBatch")) {
                        if (!binds.batches.isEmpty()) {
                            info.batches = new ArrayList<List<JsonNode>>();
                            for (TreeMap<Integer, Object> batch : binds.batches) {
                                info.batches.add(parameters(batch, "$.sql.batches[" + info.batches.size() + "]", warnings));
                            }
                        } else if (!binds.sqlBatch.isEmpty()) {
                            info.batches = new ArrayList<List<JsonNode>>();
                            for (String s : binds.sqlBatch) {
                                info.batches.add(Collections.singletonList((JsonNode) TextNode.valueOf(s)));
                            }
                        }
                        binds.batches.clear();
                        binds.sqlBatch.clear();
                    } else if (!binds.current.isEmpty()) {
                        info.parameters = parameters(binds.current, "$.sql.parameters", warnings);
                    }
                }
            }
            OpenSpan span = SpanStack.current().push(recording, Span.KIND_SQL, null, poolName, operation, System.nanoTime(),
                    null, statement, null, null, null, null, Recorder.propagation());
            span.sql = info;
            span.sqlWarnings = warnings;
            return Spans.token(span);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void exit(long token, Object statement, Object result, Throwable thrown) {
        if (token == 0L) {
            return;
        }
        try {
            OpenSpan open = Spans.top(statement);
            if (open == null) {
                return;
            }
            Span span = open.toSpan(System.nanoTime() - open.enteredAtNanos);
            List<SerializationWarning> warnings = open.sqlWarnings == null ? new ArrayList<SerializationWarning>() : open.sqlWarnings;
            if (thrown != null) {
                span.exception = Spans.exception(thrown, warnings);
            } else if (result instanceof Integer) {
                span.sql.updateCount = (Integer) result;
            } else if (result instanceof int[]) {
                span.sql.updateCounts = (int[]) result;
            } else if (result instanceof Boolean) {
                span.sql.resultSet = (Boolean) result;
                if ((Boolean) result) {
                    span.sql.rows = 0L;
                    OPEN_RESULTS.put(statement, span.sql);
                }
            } else if (result != null && implementsNamed(result.getClass(), "java.sql.ResultSet")) {
                span.sql.resultSet = Boolean.TRUE;
                span.sql.rows = 0L;
                OPEN_RESULTS.put(statement, span.sql);
            }
            if (!warnings.isEmpty()) {
                span.serializationWarnings = warnings;
            }
            Spans.complete(span);
        } catch (Throwable t) {
            Spans.abandon("jdbc exit handling", t);
        }
    }

    /** A call through the result-set wrapper's handler: a {@code next()} that returned true is one more row the application read. */
    public static void rowRead(Object resultSet, String method, Object returned) {
        if (Spans.active() == null || !method.equals("next") || !Boolean.TRUE.equals(returned)) {
            return;
        }
        try {
            Object statement = ContainerInvocation.fieldUp(resultSet, "stmt");
            SqlInfo info = statement == null ? null : OPEN_RESULTS.get(statement);
            if (info != null && info.rows != null) {
                info.rows = info.rows + 1;
            }
        } catch (Throwable ignored) {
            // observing only
        }
    }

    /** Bound parameters by position: element 0 is position 1, a position never set is null. */
    private static List<JsonNode> parameters(TreeMap<Integer, Object> bound, String path, List<SerializationWarning> warnings) {
        int size = bound.isEmpty() ? 0 : bound.lastKey();
        List<JsonNode> out = new ArrayList<JsonNode>(size);
        for (int i = 1; i <= size; i++) {
            Object v = bound.get(i);
            out.add(bound.containsKey(i) ? Spans.serialize(v, v == null ? Object.class : v.getClass(), path + "[" + (i - 1) + "]", warnings) : NullNode.getInstance());
        }
        return out;
    }

    private static String statementType(Object statement) {
        for (Class<?> c = statement.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("weblogic.jdbc.wrapper.CallableStatement")) {
                return "callable";
            }
            if (c.getName().equals("weblogic.jdbc.wrapper.PreparedStatement")) {
                return "prepared";
            }
        }
        return "statement";
    }

    private static boolean implementsNamed(Class<?> type, String interfaceName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(interfaceName) || implementsNamed(i, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
