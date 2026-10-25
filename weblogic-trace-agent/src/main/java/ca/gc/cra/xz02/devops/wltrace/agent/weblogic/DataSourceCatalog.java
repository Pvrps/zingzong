package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a WebLogic data source is, by its pool name: JNDI names, URL and database product, read
 * once per name from the config {@code JDBCSystemResource} and the {@code JDBCDataSourceRuntime}
 * MBeans. A resolution that fails is remembered too, so a data source is looked up once.
 */
public final class DataSourceCatalog {

    /** One data source as the server describes it; lists are empty and strings null when the server could not tell. */
    public static final class DataSourceDescription {
        public final String name;
        public final List<String> jndiNames;
        public final String url;
        public final String database;

        DataSourceDescription(String name, List<String> jndiNames, String url, String database) {
            this.name = name;
            this.jndiNames = Collections.unmodifiableList(jndiNames);
            this.url = url;
            this.database = database;
        }
    }

    private final RuntimeMBeans runtimeMBeans;
    private final AgentLog log;
    private final Map<String, DataSourceDescription> cache = new ConcurrentHashMap<>();

    public DataSourceCatalog(RuntimeMBeans runtimeMBeans, AgentLog log) {
        this.runtimeMBeans = runtimeMBeans;
        this.log = log;
    }

    public DataSourceDescription describe(String poolName) {
        return cache.computeIfAbsent(poolName, name -> {
            DataSourceDescription resolved = resolve(name);
            log.info("data source " + name + ": jndi=" + resolved.jndiNames + " url=" + resolved.url + " database=" + resolved.database);
            return resolved;
        });
    }

    private DataSourceDescription resolve(String poolName) {
        List<String> jndiNames = new ArrayList<>();
        String url = null;
        String database = null;
        try {
            MBeanServerConnection connection = runtimeMBeans.server();
            if (connection != null) {
                Set<ObjectName> configs = connection.queryNames(new ObjectName("com.bea:Type=JDBCSystemResource,Name=" + ObjectName.quote(poolName) + ",*"), null);
                if (configs.isEmpty()) {
                    configs = connection.queryNames(new ObjectName("com.bea:Type=JDBCSystemResource,Name=" + poolName + ",*"), null);
                }
                for (ObjectName config : configs) {
                    Object resource = connection.getAttribute(config, "JDBCResource");
                    if (resource instanceof ObjectName) {
                        Object dataSourceParams = connection.getAttribute((ObjectName) resource, "JDBCDataSourceParams");
                        if (dataSourceParams instanceof ObjectName) {
                            Object names = connection.getAttribute((ObjectName) dataSourceParams, "JNDINames");
                            if (names instanceof String[]) {
                                jndiNames.addAll(Arrays.asList((String[]) names));
                            }
                        }
                        Object driverParams = connection.getAttribute((ObjectName) resource, "JDBCDriverParams");
                        if (driverParams instanceof ObjectName) {
                            url = Reflection.text(connection.getAttribute((ObjectName) driverParams, "Url"));
                        }
                    }
                    break;
                }
                for (ObjectName runtime : connection.queryNames(new ObjectName("com.bea:Type=JDBCDataSourceRuntime,Name=" + poolName + ",*"), null)) {
                    Object product = connection.getAttribute(runtime, "DatabaseProductName");
                    Object version = connection.getAttribute(runtime, "DatabaseProductVersion");
                    database = product == null ? null : product + (version == null ? "" : " " + version);
                    break;
                }
            }
        } catch (Throwable failure) {
            log.info("data source " + poolName + ": MBeans not readable (" + failure + ")");
        }
        return new DataSourceDescription(poolName, jndiNames, url, database);
    }
}
