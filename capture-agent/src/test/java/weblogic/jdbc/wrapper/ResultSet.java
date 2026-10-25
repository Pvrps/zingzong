package weblogic.jdbc.wrapper;

import java.lang.reflect.Proxy;

/**
 * Stub of WebLogic's result-set wrapper: the {@code stmt} field and the {@code postInvocationHandler}
 * through which the generated driver-specific subclass routes every call; here a proxy plays that subclass.
 */
public class ResultSet {

    private Statement stmt;
    private int remaining;

    public Object postInvocationHandler(String method, Object[] args, Object returned) {
        return returned;
    }

    public void close() {
    }

    public static java.sql.ResultSet makeResultSet(Statement statement, int rows) {
        final ResultSet wrapper = new ResultSet();
        wrapper.stmt = statement;
        wrapper.remaining = rows;
        return (java.sql.ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{java.sql.ResultSet.class}, (p, m, a) -> {
            if (m.getName().equals("next")) {
                boolean more = wrapper.remaining-- > 0;
                return wrapper.postInvocationHandler("next", a, more);
            }
            if (m.getName().equals("close")) {
                wrapper.close();
                return null;
            }
            return m.getReturnType() == boolean.class ? false : m.getReturnType().isPrimitive() ? 0 : null;
        });
    }
}
