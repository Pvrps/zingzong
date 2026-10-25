package weblogic.jdbc.wrapper;

import java.sql.SQLException;

/** Stub of WebLogic's statement wrapper: the {@code sql} and {@code conn} fields and the {@code execute*} methods the hook advises. */
public class Statement {

    public String sql;
    public Connection conn;
    /** Rows the next query yields, or the exception the next execution throws. */
    public int rowsToYield;
    public SQLException failure;

    public Statement(Connection conn) {
        this.conn = conn;
    }

    public java.sql.ResultSet executeQuery(String sql) throws SQLException {
        this.sql = sql;
        return executeQuery();
    }

    public int executeUpdate(String sql) throws SQLException {
        this.sql = sql;
        return executeUpdate();
    }

    public java.sql.ResultSet executeQuery() throws SQLException {
        fail();
        return ResultSet.makeResultSet(this, rowsToYield);
    }

    public int executeUpdate() throws SQLException {
        fail();
        return 1;
    }

    public void close() {
    }

    void fail() throws SQLException {
        if (failure != null) {
            throw failure;
        }
    }
}
