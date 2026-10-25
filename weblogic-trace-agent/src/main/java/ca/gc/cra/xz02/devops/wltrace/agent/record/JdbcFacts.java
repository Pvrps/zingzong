package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.model.SerializationWarning;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import ca.gc.cra.xz02.devops.wltrace.model.SqlInfo;

import java.util.List;

/**
 * The facts of a statement execution as the wrapper knows them before the driver runs: the data
 * source, the statement text, the bound parameters or batches, and the warnings serializing the
 * parameters produced. The outcome (count, rows, exception) is added when the execution returns.
 */
public final class JdbcFacts extends SpanFacts {

    public final Object statement;
    public final SqlInfo sql;
    public final List<SerializationWarning> warnings;

    public JdbcFacts(Object statement, SqlInfo sql, List<SerializationWarning> warnings) {
        this.statement = statement;
        this.sql = sql;
        this.warnings = warnings;
    }

    @Override
    public MethodSignature signature() {
        return MethodSignature.unknown(sql.operation, "");
    }

    @Override
    public void describe(Span span) {
        span.sql = sql;
    }

    @Override
    public String summary() {
        return sql.dataSource + "." + sql.operation;
    }
}
