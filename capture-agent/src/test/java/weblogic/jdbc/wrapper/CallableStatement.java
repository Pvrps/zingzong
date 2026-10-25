package weblogic.jdbc.wrapper;

/** Stub of WebLogic's callable-statement wrapper. */
public class CallableStatement extends PreparedStatement {

    public CallableStatement(Connection conn, String sql) {
        super(conn, sql);
    }
}
