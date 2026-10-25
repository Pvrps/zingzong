package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.DataSourceCatalog;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.JdbcWrappers;
import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.SqlInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * SQL through WebLogic's JDBC wrappers: one span per {@code execute*} with the data source, the
 * statement, the parameters bound so far and what came back. Adds to the base shape:
 * {@link #bind} keeps the parameters a prepared statement receives before it runs, and
 * {@link #rowRead} counts the rows the application steps through afterwards; the outcome is the
 * update count, the batch counts or the result set, not a serialized return value.
 */
public final class JdbcRecorder extends SpanRecorder<JdbcFacts> {

    /** The parameters bound on one prepared statement so far, and the batches added. */
    private static final class Bindings {
        final TreeMap<Integer, Object> current = new TreeMap<>();
        final List<TreeMap<Integer, Object>> batches = new ArrayList<>();
        final List<String> sqlBatch = new ArrayList<>();
    }

    private final DataSourceCatalog dataSources;
    private final Map<Object, Bindings> bindings = Collections.synchronizedMap(new WeakHashMap<>());
    /** The SQL record of each statement's last execution that produced a result set, while its rows are being read. */
    private final Map<Object, SqlInfo> openResults = Collections.synchronizedMap(new WeakHashMap<>());

    public JdbcRecorder(RecorderContext context, DataSourceCatalog dataSources) {
        super(context);
        this.dataSources = dataSources;
    }

    @Override
    public String kind() {
        return Span.KIND_SQL;
    }

    /** A prepared statement's {@code set*(index, value)}, {@code setNull}, {@code addBatch} or {@code clearParameters}. */
    public void bind(Object statement, String method, Object[] arguments) {
        if (!recordingActive()) {
            return;
        }
        try {
            Bindings bound = bindings.computeIfAbsent(statement, key -> new Bindings());
            synchronized (bound) {
                if (method.equals("clearParameters")) {
                    bound.current.clear();
                } else if (method.equals("addBatch")) {
                    if (arguments.length == 1 && arguments[0] instanceof String) {
                        bound.sqlBatch.add((String) arguments[0]);
                    } else {
                        bound.batches.add(new TreeMap<>(bound.current));
                    }
                } else if (arguments.length >= 2 && arguments[0] instanceof Integer) {
                    bound.current.put((Integer) arguments[0], method.equals("setNull") ? null : arguments[1]);
                }
            }
        } catch (Throwable ignored) {
            // never into the statement
        }
    }

    /** A call through the result-set wrapper's handler: a {@code next()} that returned true is one more row the application read. */
    public void rowRead(Object resultSet, String method, Object returned) {
        if (!recordingActive() || !method.equals("next") || !Boolean.TRUE.equals(returned)) {
            return;
        }
        try {
            Object statement = JdbcWrappers.statementOf(resultSet);
            SqlInfo sql = statement == null ? null : openResults.get(statement);
            if (sql != null && sql.rows != null) {
                sql.rows = sql.rows + 1;
            }
        } catch (Throwable ignored) {
            // observing only
        }
    }

    /** The statement wrapper, the {@code execute*} method name and its arguments. */
    @Override
    protected JdbcFacts readFacts(Object statement, Object operation, Object arguments) {
        String operationName = String.valueOf(operation);
        SqlInfo sql = new SqlInfo();
        sql.dataSource = JdbcWrappers.poolName(statement);
        DataSourceCatalog.DataSourceDescription dataSource = dataSources.describe(sql.dataSource);
        sql.jndiNames = dataSource.jndiNames.isEmpty() ? null : dataSource.jndiNames;
        sql.url = dataSource.url;
        sql.database = dataSource.database;
        sql.statementType = JdbcWrappers.statementType(statement);
        sql.operation = operationName;
        sql.sql = JdbcWrappers.sql(statement, (Object[]) arguments);
        List<SerializationWarning> warnings = new ArrayList<>();
        Bindings bound = bindings.get(statement);
        if (bound != null) {
            synchronized (bound) {
                if (operationName.equals("executeBatch")) {
                    sql.batches = batches(bound, warnings);
                    bound.batches.clear();
                    bound.sqlBatch.clear();
                } else if (!bound.current.isEmpty()) {
                    sql.parameters = parameters(bound.current, "$.sql.parameters", warnings);
                }
            }
        }
        return new JdbcFacts(statement, sql, warnings);
    }

    /** The update count, the batch counts or the result set the execution produced, or the exception; never a serialized return value. */
    @Override
    protected void recordOutcome(Span span, JdbcFacts facts, Object[] arguments, Object result, Throwable thrown, List<SerializationWarning> warnings) {
        warnings.addAll(facts.warnings);
        SqlInfo sql = facts.sql;
        if (thrown != null) {
            span.exception = exception(thrown, warnings);
        } else if (result instanceof Integer) {
            sql.updateCount = (Integer) result;
        } else if (result instanceof int[]) {
            sql.updateCounts = (int[]) result;
        } else if (result instanceof Boolean) {
            sql.resultSet = (Boolean) result;
            if ((Boolean) result) {
                startCountingRows(facts.statement, sql);
            }
        } else if (JdbcWrappers.isResultSet(result)) {
            sql.resultSet = Boolean.TRUE;
            startCountingRows(facts.statement, sql);
        }
    }

    private void startCountingRows(Object statement, SqlInfo sql) {
        sql.rows = 0L;
        openResults.put(statement, sql);
    }

    private List<List<JsonNode>> batches(Bindings bound, List<SerializationWarning> warnings) {
        if (!bound.batches.isEmpty()) {
            List<List<JsonNode>> batches = new ArrayList<>();
            for (TreeMap<Integer, Object> batch : bound.batches) {
                batches.add(parameters(batch, "$.sql.batches[" + batches.size() + "]", warnings));
            }
            return batches;
        }
        if (!bound.sqlBatch.isEmpty()) {
            List<List<JsonNode>> batches = new ArrayList<>();
            for (String statementText : bound.sqlBatch) {
                batches.add(Collections.singletonList(TextNode.valueOf(statementText)));
            }
            return batches;
        }
        return null;
    }

    /** Bound parameters by position: element 0 is position 1, a position never set is null. */
    private List<JsonNode> parameters(TreeMap<Integer, Object> bound, String path, List<SerializationWarning> warnings) {
        int size = bound.isEmpty() ? 0 : bound.lastKey();
        List<JsonNode> nodes = new ArrayList<>(size);
        for (int position = 1; position <= size; position++) {
            if (!bound.containsKey(position)) {
                nodes.add(NullNode.getInstance());
                continue;
            }
            Object value = bound.get(position);
            nodes.add(serialize(value, value == null ? Object.class : value.getClass(), path + "[" + (position - 1) + "]", warnings));
        }
        return nodes;
    }
}
