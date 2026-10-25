package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

/**
 * Reads WebLogic's JDBC wrapper objects by field and method name: the pool name behind a
 * statement's {@code conn}, the SQL text in a statement's {@code sql} field, the wrapper kind,
 * and the statement behind a result set's {@code stmt}.
 */
public final class JdbcWrappers {

    public static final String PREPARED_STATEMENT = "weblogic.jdbc.wrapper.PreparedStatement";
    public static final String CALLABLE_STATEMENT = "weblogic.jdbc.wrapper.CallableStatement";
    private static final String RESULT_SET_INTERFACE = "java.sql.ResultSet";

    private JdbcWrappers() {
    }

    /** The data source (pool) name of the connection the statement came from; {@code ?} when unreadable. */
    public static String poolName(Object statement) {
        Object connection = Reflection.field(statement, "conn");
        Object pool = Reflection.call(connection, "getPoolName");
        return pool == null ? "?" : pool.toString();
    }

    /** The SQL text: the prepared statement's {@code sql} field, else the string argument of a plain {@code execute*(sql)}. */
    public static String sql(Object statement, Object[] arguments) {
        Object sql = Reflection.field(statement, "sql");
        if (sql != null) {
            return sql.toString();
        }
        return arguments != null && arguments.length > 0 && arguments[0] instanceof String ? (String) arguments[0] : null;
    }

    /** {@code callable}, {@code prepared} or {@code statement}, by the wrapper's superclasses. */
    public static String statementType(Object statement) {
        if (Reflection.extendsNamed(statement, CALLABLE_STATEMENT)) {
            return "callable";
        }
        if (Reflection.extendsNamed(statement, PREPARED_STATEMENT)) {
            return "prepared";
        }
        return "statement";
    }

    /** The statement wrapper behind a result-set wrapper, or null. */
    public static Object statementOf(Object resultSet) {
        return Reflection.field(resultSet, "stmt");
    }

    /** True when the execution result is a result set, recognised by interface name because {@code java.sql} is not visible to the agent. */
    public static boolean isResultSet(Object result) {
        return result != null && Reflection.implementsNamed(result.getClass(), RESULT_SET_INTERFACE);
    }
}
