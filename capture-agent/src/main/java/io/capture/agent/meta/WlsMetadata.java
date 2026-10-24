package io.capture.agent.meta;

import io.capture.agent.AgentLog;
import io.capture.trace.TraceInfo;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves what the server knows about a bean class: application, module, EJB name, business
 * interfaces, every JNDI name it is bound under; and the list of deployed applications with
 * their source paths. Everything goes through {@code javax.management} and {@code javax.naming}
 * plus reflection by name; nothing links against weblogic.jar.
 *
 * Per bean class the answer is cached in a {@link ClassValue}: keyed by the Class object,
 * released with it. Failures degrade to null fields and are logged once.
 *
 * Sources, in order:
 * <ol>
 *   <li>the class's own annotations: EJB name, business interfaces (EjbAnnotations)</li>
 *   <li>the runtime MBean tree ({@code com.bea:Type=*EJBRuntime,Name=<ejbName>,*}) on the
 *       platform MBeanServer, falling back to the server's runtime MBeanServer from JNDI
 *       ({@code java:comp/env/jmx/runtime}): application name and module id</li>
 *   <li>an in-process JNDI lookup of {@code java:global/<app>/<module>/<ejbName>[!iface]}: the
 *       generated client object carries the container's BeanInfo, whose
 *       {@code getBusinessJNDINames()} lists every name per interface, descriptor mappings
 *       (weblogic-ejb-jar.xml) included, and whose {@code getBeanClassName()} confirms the match</li>
 * </ol>
 */
public final class WlsMetadata {

    private static final ClassValue<BeanMeta> CACHE = new ClassValue<BeanMeta>() {
        @Override
        protected BeanMeta computeValue(Class<?> type) {
            return resolve(type);
        }
    };

    private static final AtomicReference<MBeanServerConnection> SERVER = new AtomicReference<MBeanServerConnection>();
    private static final AtomicBoolean serverProbed = new AtomicBoolean();
    private static final AtomicBoolean warnedServer = new AtomicBoolean();
    private static final AtomicBoolean warnedDeployments = new AtomicBoolean();
    private static final AtomicReference<List<TraceInfo.Deployment>> DEPLOYMENTS = new AtomicReference<List<TraceInfo.Deployment>>();

    private WlsMetadata() {
    }

    /** What the container hook knows at the call site and the annotations may not say: the descriptor's EJB name and the container's BeanInfo. */
    public static final class Hint {
        final String ejbName;
        final Object beanInfo;

        public Hint(String ejbName, Object beanInfo) {
            this.ejbName = ejbName;
            this.beanInfo = beanInfo;
        }
    }

    private static final ThreadLocal<Hint> HINT = new ThreadLocal<Hint>();

    public static BeanMeta of(Class<?> beanClass) {
        if (beanClass == null) {
            return null;
        }
        return CACHE.get(beanClass);
    }

    /** As {@link #of(Class)}, with the container's hints used the first time this class is resolved. */
    public static BeanMeta of(Class<?> beanClass, Hint hint) {
        if (beanClass == null) {
            return null;
        }
        HINT.set(hint);
        try {
            return CACHE.get(beanClass);
        } finally {
            HINT.remove();
        }
    }

    /** Forget cross-class state (deployments list); per-class answers stay with their classes. */
    public static void reset() {
        DEPLOYMENTS.set(null);
    }

    // ---- per bean --------------------------------------------------------------------------

    private static BeanMeta resolve(Class<?> beanClass) {
        Hint hint = HINT.get();
        String ejbName = hint != null && hint.ejbName != null ? hint.ejbName : EjbAnnotations.ejbName(beanClass);
        Map<String, String> interfaces = EjbAnnotations.interfaces(beanClass);
        // EJB 2.x component interfaces (no annotations: the bean does not even implement them) come from the container.
        Set<String> componentInterfaces = new java.util.HashSet<String>();
        if (hint != null && hint.beanInfo != null) {
            Object remote = ContainerInvocation.call(hint.beanInfo, "getRemoteInterfaceClass");
            Object local = ContainerInvocation.call(hint.beanInfo, "getLocalInterfaceClass");
            if (remote instanceof Class && !interfaces.containsKey(((Class<?>) remote).getName())) {
                interfaces.put(((Class<?>) remote).getName(), BeanMeta.REMOTE);
                componentInterfaces.add(((Class<?>) remote).getName());
            }
            if (local instanceof Class && !interfaces.containsKey(((Class<?>) local).getName())) {
                interfaces.put(((Class<?>) local).getName(), BeanMeta.LOCAL);
                componentInterfaces.add(((Class<?>) local).getName());
            }
        }
        String app = null;
        String module = null;
        Object clientObject = null;
        String problem = null;
        Set<String> resolvedNames = new TreeSet<String>(); // portable names that actually resolved in JNDI
        try {
            MBeanServerConnection server = server();
            if (server == null) {
                problem = "no MBeanServer with com.bea runtime MBeans reachable";
            } else {
                List<String[]> candidates = candidates(server, ejbName);
                if (candidates.isEmpty()) {
                    problem = "no *EJBRuntime MBean named " + ejbName;
                }
                for (String[] candidate : candidates) {
                    Object found = lookupClientObject(candidate[0], candidate[1], ejbName, interfaces.keySet(), resolvedNames);
                    if (found != null && beanClass.getName().equals(beanClassNameOf(found))) {
                        app = candidate[0];
                        module = candidate[1];
                        clientObject = found;
                        break;
                    }
                    if (app == null && candidates.size() == 1) {
                        app = candidate[0];
                        module = candidate[1];
                        clientObject = found;
                    }
                }
            }
        } catch (Throwable t) {
            problem = "metadata lookup failed: " + t;
        }

        Set<String> names = new TreeSet<String>(resolvedNames);
        Object infoSource = clientObject != null ? clientObject : hint == null ? null : hint.beanInfo;
        if (infoSource != null) {
            Map<String, String> fromContainer = businessJndiNames(infoSource, names);
            for (Map.Entry<String, String> e : fromContainer.entrySet()) {
                if (!interfaces.containsKey(e.getKey())) {
                    interfaces.put(e.getKey(), e.getValue());
                }
            }
            // EJB 2.x: the home's JNDI names (weblogic-ejb-jar.xml <jndi-name>), the way a client reaches the bean.
            Object info = beanInfo(infoSource);
            if (ContainerInvocation.call(info, "getHomeInterfaceClass") != null) {
                Object home = ContainerInvocation.call(info, "getJNDINameAsString");
                if (home != null && !home.toString().isEmpty()) {
                    names.add(home.toString());
                }
            }
            if (ContainerInvocation.call(info, "getLocalHomeInterfaceClass") != null) {
                Object home = ContainerInvocation.call(info, "getLocalJNDINameAsString");
                if (home != null && !home.toString().isEmpty()) {
                    names.add(home.toString());
                }
            }
        }
        BeanMeta partial = new BeanMeta(app, module, ejbName, beanClass.getName(), interfaces, null, problem);
        for (String iface : interfaces.keySet()) {
            String global = componentInterfaces.contains(iface) ? null : partial.globalName(iface);
            if (global != null) {
                names.add(global);
            }
        }
        BeanMeta meta = new BeanMeta(app, module, ejbName, beanClass.getName(), interfaces, new ArrayList<String>(names), problem);
        meta.componentInterfaces = componentInterfaces;
        meta.beanType = beanType(infoSource != null ? beanInfo(infoSource) : null);
        AgentLog.info("metadata " + beanClass.getName() + ": app=" + app + " module=" + module + " ejb=" + ejbName
                + " interfaces=" + interfaces + " jndi=" + meta.jndiNames
                + " via=" + (clientObject == null ? "none" : clientObject.getClass().getName())
                + (problem == null ? "" : " (" + problem + ")"));
        return meta;
    }

    /** [application, module] pairs of *EJBRuntime MBeans with this EJB name. */
    private static List<String[]> candidates(MBeanServerConnection server, String ejbName) throws Exception {
        List<String[]> result = new ArrayList<String[]>();
        Set<ObjectName> names = server.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,Name=" + ObjectName.quote(ejbName) + ",*"), null);
        if (names.isEmpty()) {
            names = server.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,Name=" + ejbName + ",*"), null);
        }
        if (names.isEmpty()) {
            // A message-driven bean's runtime MBean is named <ejb>_<destination> (one per
            // destination); the EJB name is only in its EJBName attribute.
            names = new java.util.LinkedHashSet<ObjectName>();
            for (ObjectName any : server.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,*"), null)) {
                try {
                    if (ejbName.equals(server.getAttribute(any, "EJBName"))) {
                        names.add(any);
                    }
                } catch (Exception ignored) {
                    // not an EJB runtime after all
                }
            }
        }
        for (ObjectName name : names) {
            String app = name.getKeyProperty("ApplicationRuntime");
            String moduleId = name.getKeyProperty("EJBComponentRuntime");
            if (app == null || moduleId == null) {
                continue;
            }
            result.add(new String[]{unquote(app), moduleName(unquote(moduleId))});
        }
        return result;
    }

    /** Key property values are quoted only when they contain special characters. */
    private static String unquote(String value) {
        return value.startsWith("\"") ? ObjectName.unquote(value) : value;
    }

    /** "orders-ejb.jar" → "orders-ejb": the java:global module name is the archive name without its extension. */
    static String moduleName(String moduleId) {
        int dot = moduleId.lastIndexOf('.');
        return dot > 0 ? moduleId.substring(0, dot) : moduleId;
    }

    /** The client object under one of the portable names; the name that resolved is added to {@code resolvedNames}. */
    private static Object lookupClientObject(String app, String module, String ejbName, Set<String> interfaces, Set<String> resolvedNames) {
        List<String> tries = new ArrayList<String>();
        tries.add("java:global/" + app + "/" + module + "/" + ejbName);
        for (String iface : interfaces) {
            tries.add("java:global/" + app + "/" + module + "/" + ejbName + "!" + iface);
        }
        for (String name : tries) {
            try {
                Object found = new javax.naming.InitialContext().lookup(name);
                if (found != null) {
                    resolvedNames.add(name);
                    return found;
                }
            } catch (Throwable ignored) {
                // try the next form
            }
        }
        return null;
    }

    /** BaseLocalObject/BaseRemoteObject.beanInfo → BeanInfo.getBeanClassName(). */
    private static String beanClassNameOf(Object clientObject) {
        Object beanInfo = beanInfo(clientObject);
        if (beanInfo == null) {
            return null;
        }
        try {
            Method m = beanInfo.getClass().getMethod("getBeanClassName");
            m.setAccessible(true);
            Object name = m.invoke(beanInfo);
            return name == null ? null : name.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** SessionBeanInfo.getBusinessJNDINames(): interface → names; collects the names, returns interface → view. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> businessJndiNames(Object clientObject, Set<String> into) {
        Map<String, String> views = new LinkedHashMap<String, String>();
        Object beanInfo = beanInfo(clientObject);
        if (beanInfo == null) {
            return views;
        }
        try {
            Method m = beanInfo.getClass().getMethod("getBusinessJNDINames");
            m.setAccessible(true);
            Map<Class<?>, Set<String>> byInterface = (Map<Class<?>, Set<String>>) m.invoke(beanInfo);
            for (Map.Entry<Class<?>, Set<String>> e : byInterface.entrySet()) {
                String view = EjbAnnotations.viewOf(e.getKey());
                views.put(e.getKey().getName(), view == null ? BeanMeta.LOCAL : view);
                for (String name : e.getValue()) {
                    into.add(name);
                }
            }
        } catch (Throwable ignored) {
            // not a session bean client object or a different container version: deterministic names only
        }
        return views;
    }

    /**
     * The container's BeanInfo behind what an in-process JNDI lookup returns: the
     * {@code beanInfo} field of a BaseLocalObject/BaseRemoteObject, reached through the
     * container's own objects (14.1.2.0.0: local view → generated Impl.__WL_localObject;
     * remote view → Proxy → RemoteBusinessIntfProxy.ejbObject → Impl_CBV → Impl.__WL_remoteObject).
     * A bounded search over WebLogic-typed fields rather than those names, so a different
     * wrapping in another patch level still resolves.
     */

    /** weblogic.ejb.spi.SessionBeanInfo.isStateless/isStateful/isSingleton; a MessageDrivenBeanInfo is message-driven. */
    private static String beanType(Object info) {
        if (info == null) {
            return null;
        }
        if (implementsNamed(info.getClass(), "weblogic.ejb.container.interfaces.MessageDrivenBeanInfo")) {
            return "MESSAGE_DRIVEN";
        }
        for (String[] probe : new String[][]{{"isStateful", "STATEFUL"}, {"isSingleton", "SINGLETON"}, {"isStateless", "STATELESS"}}) {
            if (Boolean.TRUE.equals(ContainerInvocation.call(info, probe[0]))) {
                return probe[1];
            }
        }
        return null;
    }

    private static boolean implementsNamed(Class<?> type, String interfaceName) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(interfaceName) || implementsNamed(i, interfaceName)) {
                    return true;
                }
            }
        }
        return false;
    }
    static Object beanInfo(Object clientObject) {
        return findBeanInfo(clientObject, 0);
    }

    private static Object findBeanInfo(Object o, int depth) {
        if (o == null || depth > 4) {
            return null;
        }
        if (implementsNamed(o.getClass(), "weblogic.ejb.container.interfaces.BeanInfo")) {
            return o; // the container's BeanInfo itself (the container hook hands it over)
        }
        if (Proxy.isProxyClass(o.getClass())) {
            return findBeanInfo(Proxy.getInvocationHandler(o), depth + 1);
        }
        Object direct = ContainerInvocation.fieldUp(o, "beanInfo");
        if (direct != null) {
            return direct;
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object held = f.get(o);
                    if (held == null || held instanceof ClassLoader || held.getClass().isArray()
                            || held.getClass().getName().startsWith("java.")) {
                        continue;
                    }
                    Object found = findBeanInfo(held, depth + 1);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                    // next field
                }
            }
        }
        return null;
    }

    // ---- MBean server ------------------------------------------------------------------------

    /**
     * The platform MBeanServer when it carries the WebLogic runtime tree (WebLogic registers its
     * runtime MBeans there by default, PlatformMBeanServerUsed=true), else the server's runtime
     * MBeanServer from JNDI. The answer is logged once.
     */
    public static MBeanServerConnection server() {
        MBeanServerConnection server = SERVER.get();
        if (server != null || serverProbed.get()) {
            return server;
        }
        boolean contextSet = ensureContextLoader();
        StringBuilder tried = new StringBuilder();
        try {
            ObjectName probe = new ObjectName("com.bea:Type=EJBComponentRuntime,*");
            MBeanServerConnection platform = ManagementFactory.getPlatformMBeanServer();
            int count = platform.queryNames(probe, null).size();
            if (count > 0) {
                server = platform;
                AgentLog.info("runtime MBeans: platform MBeanServer, " + count + " EJBComponentRuntime MBean(s), no credentials needed");
            }
            tried.append("platform MBeanServer: ").append(count);
            if (server == null) {
                // WebLogic creates its MBeanServers through the factory, which keeps a list:
                // reachable from any thread, no credentials, no JNDI component context.
                List<javax.management.MBeanServer> all = javax.management.MBeanServerFactory.findMBeanServer(null);
                for (javax.management.MBeanServer candidate : all) {
                    int n = candidate.queryNames(probe, null).size();
                    if (n > 0) {
                        server = candidate;
                        AgentLog.info("runtime MBeans: platform MBeanServer has none; MBeanServerFactory.findMBeanServer lists "
                                + all.size() + ", one with " + n + " EJBComponentRuntime MBean(s), no credentials needed");
                        break;
                    }
                }
                tried.append("; MBeanServerFactory: ").append(all.size()).append(" server(s)");
            }
            if (server == null) {
                for (String jndi : new String[]{"java:comp/env/jmx/runtime", "java:comp/jmx/runtime"}) {
                    try {
                        Object fromJndi = new javax.naming.InitialContext().lookup(jndi);
                        if (fromJndi instanceof MBeanServerConnection) {
                            int n = ((MBeanServerConnection) fromJndi).queryNames(probe, null).size();
                            server = (MBeanServerConnection) fromJndi;
                            AgentLog.info("runtime MBeans: runtime MBeanServer from JNDI " + jndi + ", " + n
                                    + " EJBComponentRuntime MBean(s), no credentials needed (" + tried + ")");
                            break;
                        }
                    } catch (Throwable t) {
                        tried.append("; ").append(jndi).append(": ").append(t);
                    }
                }
            }
            if (server != null) {
                serverProbed.set(true);
            } else if (warnedServer.compareAndSet(false, true)) {
                AgentLog.warn("runtime MBeans unreachable (" + tried + "); EJB metadata will be partial");
            }
        } catch (Throwable t) {
            if (warnedServer.compareAndSet(false, true)) {
                AgentLog.warn("runtime MBeans unreachable: " + t + " (" + tried + "); EJB metadata will be partial");
            }
        } finally {
            restoreContextLoader(contextSet);
        }
        SERVER.set(server);
        return server;
    }

    /**
     * WebLogic's MBeanServer wrapper asserts a context class loader; control threads (JMX,
     * attach) may have none. True when one was set and must be cleared again.
     */
    private static boolean ensureContextLoader() {
        Thread thread = Thread.currentThread();
        if (thread.getContextClassLoader() != null) {
            return false;
        }
        thread.setContextClassLoader(ClassLoader.getSystemClassLoader());
        return true;
    }

    private static void restoreContextLoader(boolean contextSet) {
        if (contextSet) {
            Thread.currentThread().setContextClassLoader(null);
        }
    }

    // ---- deployments -------------------------------------------------------------------------

    /** All applications in the domain configuration with their source paths; cached until {@link #reset()}. */
    public static List<TraceInfo.Deployment> deployments() {
        List<TraceInfo.Deployment> cached = DEPLOYMENTS.get();
        if (cached != null) {
            return cached;
        }
        List<TraceInfo.Deployment> result = new ArrayList<TraceInfo.Deployment>();
        boolean contextSet = ensureContextLoader();
        try {
            MBeanServerConnection server = server();
            if (server != null) {
                ObjectName service = new ObjectName("com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean");
                ObjectName domain = (ObjectName) server.getAttribute(service, "DomainConfiguration");
                ObjectName[] apps = (ObjectName[]) server.getAttribute(domain, "AppDeployments");
                for (ObjectName app : apps) {
                    TraceInfo.Deployment d = new TraceInfo.Deployment();
                    d.name = String.valueOf(server.getAttribute(app, "Name"));
                    Object path = server.getAttribute(app, "SourcePath");
                    d.sourcePath = path == null ? null : path.toString();
                    Object type = server.getAttribute(app, "ModuleType");
                    d.moduleType = type == null ? null : type.toString();
                    result.add(d);
                }
            }
        } catch (Throwable t) {
            if (warnedDeployments.compareAndSet(false, true)) {
                AgentLog.warn("deployments unavailable from RuntimeService.DomainConfiguration.AppDeployments: " + t);
            }
        } finally {
            restoreContextLoader(contextSet);
        }
        List<TraceInfo.Deployment> unmodifiable = Collections.unmodifiableList(result);
        DEPLOYMENTS.set(unmodifiable);
        return unmodifiable;
    }
}
