package io.capture.agent.hook;

/**
 * WebLogic's JDBC wrapper classes: every statement a data source hands out is a
 * {@code weblogic.jdbc.wrapper.Statement} / {@code PreparedStatement} / {@code CallableStatement}
 * around the driver's object, and these base classes implement the JDBC methods themselves
 * (the generated {@code PreparedStatement_<driver class>} subclasses add only vendor extras).
 * Advising them records SQL for any driver, without touching the driver. Verified by javap on
 * 12.2.1.4.0 and 14.1.2.0.0 (docs/container-hook.md, "JDBC").
 */
public final class JdbcTargets {

    public static final String STATEMENT = "weblogic.jdbc.wrapper.Statement";
    public static final String PREPARED_STATEMENT = "weblogic.jdbc.wrapper.PreparedStatement";
    public static final String CALLABLE_STATEMENT = "weblogic.jdbc.wrapper.CallableStatement";
    public static final String RESULT_SET = "weblogic.jdbc.wrapper.ResultSet";

    static final String[] ALL = {STATEMENT, PREPARED_STATEMENT, CALLABLE_STATEMENT, RESULT_SET};

    private JdbcTargets() {
    }

    /** True when this JVM has the wrapper classes on its system class path (a WebLogic server). */
    public static boolean present() {
        for (String name : ALL) {
            if (ClassLoader.getSystemResource(name.replace('.', '/') + ".class") == null) {
                return false;
            }
        }
        return true;
    }

    static boolean isNamed(String typeName) {
        for (String name : ALL) {
            if (name.equals(typeName)) {
                return true;
            }
        }
        return false;
    }
}
