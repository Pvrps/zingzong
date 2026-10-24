package io.capture.agent.meta;

import io.capture.agent.AgentLog;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a WebLogic data source is, by its pool name: JNDI names, URL and database product,
 * read once per name from the server's MBeans (the config {@code JDBCSystemResource} and the
 * {@code JDBCDataSourceRuntime}); docs/container-hook.md, "JDBC". Resolution failures are
 * remembered too, so a data source is looked up once.
 */
public final class WlsDataSources {

    public static final class DataSource {
        public final String name;
        public final List<String> jndiNames;
        public final String url;
        public final String database;

        DataSource(String name, List<String> jndiNames, String url, String database) {
            this.name = name;
            this.jndiNames = jndiNames;
            this.url = url;
            this.database = database;
        }
    }

    private static final Map<String, DataSource> CACHE = new ConcurrentHashMap<String, DataSource>();

    private WlsDataSources() {
    }

    public static DataSource describe(String poolName) {
        if (poolName == null) {
            return null;
        }
        DataSource cached = CACHE.get(poolName);
        if (cached != null) {
            return cached;
        }
        DataSource resolved = resolve(poolName);
        CACHE.put(poolName, resolved);
        AgentLog.info("data source " + poolName + ": jndi=" + resolved.jndiNames + " url=" + resolved.url + " database=" + resolved.database);
        return resolved;
    }

    private static DataSource resolve(String poolName) {
        List<String> jndi = new ArrayList<String>();
        String url = null;
        String database = null;
        try {
            MBeanServerConnection server = WlsMetadata.server();
            if (server != null) {
                Set<ObjectName> configs = server.queryNames(new ObjectName("com.bea:Type=JDBCSystemResource,Name=" + ObjectName.quote(poolName) + ",*"), null);
                if (configs.isEmpty()) {
                    configs = server.queryNames(new ObjectName("com.bea:Type=JDBCSystemResource,Name=" + poolName + ",*"), null);
                }
                for (ObjectName config : configs) {
                    Object resource = server.getAttribute(config, "JDBCResource");
                    if (resource instanceof ObjectName) {
                        Object params = server.getAttribute((ObjectName) resource, "JDBCDataSourceParams");
                        if (params instanceof ObjectName) {
                            Object names = server.getAttribute((ObjectName) params, "JNDINames");
                            if (names instanceof String[]) {
                                jndi.addAll(Arrays.asList((String[]) names));
                            }
                        }
                        Object driver = server.getAttribute((ObjectName) resource, "JDBCDriverParams");
                        if (driver instanceof ObjectName) {
                            Object u = server.getAttribute((ObjectName) driver, "Url");
                            url = u == null ? null : u.toString();
                        }
                    }
                    break;
                }
                for (ObjectName runtime : server.queryNames(new ObjectName("com.bea:Type=JDBCDataSourceRuntime,Name=" + poolName + ",*"), null)) {
                    Object product = server.getAttribute(runtime, "DatabaseProductName");
                    Object version = server.getAttribute(runtime, "DatabaseProductVersion");
                    database = product == null ? null : product + (version == null ? "" : " " + version);
                    break;
                }
            }
        } catch (Throwable t) {
            AgentLog.info("data source " + poolName + ": MBeans not readable (" + t + ")");
        }
        return new DataSource(poolName, jndi, url, database);
    }
}
