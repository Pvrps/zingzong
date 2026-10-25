package weblogic.jdbc.wrapper;

/** Stub of WebLogic's connection wrapper: the pool name the agent reads. */
public final class Connection {

    private final String poolName;

    public Connection(String poolName) {
        this.poolName = poolName;
    }

    public String getPoolName() {
        return poolName;
    }
}
