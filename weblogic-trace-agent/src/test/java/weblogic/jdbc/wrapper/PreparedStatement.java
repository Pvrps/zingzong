package weblogic.jdbc.wrapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Stub of WebLogic's prepared-statement wrapper: the {@code set*}, {@code addBatch} and {@code clearParameters} the hook advises. */
public class PreparedStatement extends Statement {

    public final List<Object> bound = new ArrayList<Object>();
    public int batches;

    public PreparedStatement(Connection conn, String sql) {
        super(conn);
        this.sql = sql;
    }

    public void setLong(int index, long value) {
        bound.add(value);
    }

    public void setString(int index, String value) {
        bound.add(value);
    }

    public void setNull(int index, int sqlType) {
        bound.add(null);
    }

    public void clearParameters() {
        bound.clear();
    }

    public void addBatch() {
        batches++;
    }

    public int[] executeBatch() throws SQLException {
        fail();
        int[] counts = new int[batches];
        java.util.Arrays.fill(counts, 1);
        return counts;
    }
}
