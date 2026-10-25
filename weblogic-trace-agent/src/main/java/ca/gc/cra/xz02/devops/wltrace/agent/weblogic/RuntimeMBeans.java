package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.model.TraceInfo;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The MBeanServer that carries WebLogic's {@code com.bea} runtime tree, probed once: the platform
 * server, one of {@code MBeanServerFactory.findMBeanServer}, or the runtime server from JNDI.
 * Reads the EJB runtime MBeans of a bean and the domain's application deployments. WebLogic's
 * MBeanServer wrapper asserts a context class loader, which control threads lack, so every call
 * runs with one set.
 */
public final class RuntimeMBeans {

    private static final String RUNTIME_SERVICE = "com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean";
    private static final String[] RUNTIME_SERVER_JNDI_NAMES = {"java:comp/env/jmx/runtime", "java:comp/jmx/runtime"};

    /** An EJB's position in the runtime tree: application and module. */
    public static final class EjbRuntime {
        public final String application;
        public final String module;

        EjbRuntime(String application, String module) {
            this.application = application;
            this.module = module;
        }
    }

    private final AgentLog log;
    private final AtomicReference<MBeanServerConnection> server = new AtomicReference<>();
    private final AtomicBoolean probed = new AtomicBoolean();
    private final AtomicReference<List<TraceInfo.Deployment>> deployments = new AtomicReference<>();

    public RuntimeMBeans(AgentLog log) {
        this.log = log;
    }

    /** The MBeanServer with the runtime tree, or null when none is reachable (warned once). */
    public MBeanServerConnection server() {
        MBeanServerConnection found = server.get();
        if (found != null || probed.get()) {
            return found;
        }
        boolean contextSet = ensureContextLoader();
        StringBuilder tried = new StringBuilder();
        try {
            found = probe(tried);
            if (found != null) {
                probed.set(true);
            } else {
                log.warnOnce("runtime-mbeans", "runtime MBeans unreachable (" + tried + "); EJB metadata will be partial");
            }
        } catch (Throwable failure) {
            log.warnOnce("runtime-mbeans", "runtime MBeans unreachable: " + failure + " (" + tried + "); EJB metadata will be partial");
        } finally {
            restoreContextLoader(contextSet);
        }
        server.set(found);
        return found;
    }

    private MBeanServerConnection probe(StringBuilder tried) throws Exception {
        ObjectName probe = new ObjectName("com.bea:Type=EJBComponentRuntime,*");
        MBeanServerConnection platform = ManagementFactory.getPlatformMBeanServer();
        int count = platform.queryNames(probe, null).size();
        tried.append("platform MBeanServer: ").append(count);
        if (count > 0) {
            log.info("runtime MBeans: platform MBeanServer, " + count + " EJBComponentRuntime MBean(s)");
            return platform;
        }
        List<MBeanServer> all = MBeanServerFactory.findMBeanServer(null);
        tried.append("; MBeanServerFactory: ").append(all.size()).append(" server(s)");
        for (MBeanServer candidate : all) {
            int found = candidate.queryNames(probe, null).size();
            if (found > 0) {
                log.info("runtime MBeans: MBeanServerFactory.findMBeanServer lists " + all.size() + ", one with " + found + " EJBComponentRuntime MBean(s)");
                return candidate;
            }
        }
        for (String jndiName : RUNTIME_SERVER_JNDI_NAMES) {
            try {
                Object fromJndi = new InitialContext().lookup(jndiName);
                if (fromJndi instanceof MBeanServerConnection) {
                    MBeanServerConnection runtime = (MBeanServerConnection) fromJndi;
                    log.info("runtime MBeans: runtime MBeanServer from JNDI " + jndiName + ", "
                            + runtime.queryNames(probe, null).size() + " EJBComponentRuntime MBean(s) (" + tried + ")");
                    return runtime;
                }
            } catch (Throwable failure) {
                tried.append("; ").append(jndiName).append(": ").append(failure);
            }
        }
        return null;
    }

    /**
     * The {@code *EJBRuntime} MBeans named after an EJB, as application and module. A
     * message-driven bean's runtime MBean is named {@code <ejb>_<destination>}, so the EJB name is
     * matched on the {@code EJBName} attribute when the name key misses.
     */
    public List<EjbRuntime> ejbRuntimes(MBeanServerConnection connection, String ejbName) throws Exception {
        Set<ObjectName> names = connection.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,Name=" + ObjectName.quote(ejbName) + ",*"), null);
        if (names.isEmpty()) {
            names = connection.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,Name=" + ejbName + ",*"), null);
        }
        if (names.isEmpty()) {
            names = new LinkedHashSet<>();
            for (ObjectName any : connection.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,*"), null)) {
                try {
                    if (ejbName.equals(connection.getAttribute(any, "EJBName"))) {
                        names.add(any);
                    }
                } catch (Exception ignored) {
                    // not an EJB runtime after all
                }
            }
        }
        List<EjbRuntime> result = new ArrayList<>();
        for (ObjectName name : names) {
            String application = name.getKeyProperty("ApplicationRuntime");
            String moduleId = name.getKeyProperty("EJBComponentRuntime");
            if (application != null && moduleId != null) {
                result.add(new EjbRuntime(unquote(application), moduleName(unquote(moduleId))));
            }
        }
        return result;
    }

    /** All applications in the domain configuration with their source paths; cached until {@link #reset()}. */
    public List<TraceInfo.Deployment> deployments() {
        List<TraceInfo.Deployment> cached = deployments.get();
        if (cached != null) {
            return cached;
        }
        List<TraceInfo.Deployment> result = new ArrayList<>();
        boolean contextSet = ensureContextLoader();
        try {
            MBeanServerConnection connection = server();
            if (connection != null) {
                ObjectName service = new ObjectName(RUNTIME_SERVICE);
                ObjectName domain = (ObjectName) connection.getAttribute(service, "DomainConfiguration");
                for (ObjectName application : (ObjectName[]) connection.getAttribute(domain, "AppDeployments")) {
                    TraceInfo.Deployment deployment = new TraceInfo.Deployment();
                    deployment.name = String.valueOf(connection.getAttribute(application, "Name"));
                    deployment.sourcePath = Reflection.text(connection.getAttribute(application, "SourcePath"));
                    deployment.moduleType = Reflection.text(connection.getAttribute(application, "ModuleType"));
                    result.add(deployment);
                }
            }
        } catch (Throwable failure) {
            log.warnOnce("deployments", "deployments unavailable from RuntimeService.DomainConfiguration.AppDeployments: " + failure);
        } finally {
            restoreContextLoader(contextSet);
        }
        List<TraceInfo.Deployment> unmodifiable = Collections.unmodifiableList(result);
        deployments.set(unmodifiable);
        return unmodifiable;
    }

    /** Forgets the deployments list, so the next recording reads it again. */
    public void reset() {
        deployments.set(null);
    }

    /** Sets a context class loader for the current thread when it has none; returns whether it did. */
    public static boolean ensureContextLoader() {
        Thread thread = Thread.currentThread();
        if (thread.getContextClassLoader() != null) {
            return false;
        }
        thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
        return true;
    }

    public static void restoreContextLoader(boolean contextSet) {
        if (contextSet) {
            Thread.currentThread().setContextClassLoader(null);
        }
    }

    private static String unquote(String value) {
        return value.startsWith("\"") ? ObjectName.unquote(value) : value;
    }

    /** {@code orders-ejb.jar} to {@code orders-ejb}: the {@code java:global} module name is the archive name without its extension. */
    static String moduleName(String moduleId) {
        int dot = moduleId.lastIndexOf('.');
        return dot > 0 ? moduleId.substring(0, dot) : moduleId;
    }
}
