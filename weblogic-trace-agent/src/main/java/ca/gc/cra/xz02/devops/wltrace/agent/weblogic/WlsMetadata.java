package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.model.TraceInfo;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves what the server knows about a bean class (application, module, EJB name, business
 * interfaces, every JNDI name it is bound under, bean type) and the list of deployed
 * applications, through {@code javax.management}, {@code javax.naming} and reflection by name.
 * Per bean class the answer is cached in a {@link ClassValue}; failures degrade to null fields
 * and are logged once.
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

    /** What the container knows at the call site: the descriptor's EJB name and the bean's {@code BeanInfo}. */
    public static final class Hint {
        final String ejbName;
        final Object beanInfo;

        public Hint(String ejbName, Object beanInfo) {
            this.ejbName = ejbName;
            this.beanInfo = beanInfo;
        }
    }

    private static final ThreadLocal<Hint> HINT = new ThreadLocal<Hint>();

    /** The metadata of a bean class; the hint is used the first time the class is resolved. */
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

    /** Forgets the deployments list; per-class answers stay with their classes. */
    public static void reset() {
        DEPLOYMENTS.set(null);
    }

    private static BeanMeta resolve(Class<?> beanClass) {
        Hint hint = HINT.get();
        Object hintInfo = hint == null ? null : hint.beanInfo;
        String ejbName = hint != null && hint.ejbName != null ? hint.ejbName : str(ContainerInvocation.call(hintInfo, "getEJBName"));
        if (ejbName == null) {
            ejbName = beanClass.getSimpleName();
        }
        Map<String, String> interfaces = businessInterfaces(hintInfo);
        Set<String> componentInterfaces = new HashSet<String>();
        for (String[] view : new String[][]{{"getRemoteInterfaceClass", BeanMeta.REMOTE}, {"getLocalInterfaceClass", BeanMeta.LOCAL}}) {
            Object component = ContainerInvocation.call(hintInfo, view[0]);
            if (component instanceof Class && !interfaces.containsKey(((Class<?>) component).getName())) {
                interfaces.put(((Class<?>) component).getName(), view[1]);
                componentInterfaces.add(((Class<?>) component).getName());
            }
        }
        String app = null;
        String module = null;
        Object clientObject = null;
        String problem = null;
        Set<String> resolvedNames = new TreeSet<String>();
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
        Object info = beanInfo(clientObject != null ? clientObject : hintInfo);
        if (info != null) {
            Map<String, String> fromContainer = businessJndiNames(info, names);
            for (Map.Entry<String, String> e : fromContainer.entrySet()) {
                if (!interfaces.containsKey(e.getKey())) {
                    interfaces.put(e.getKey(), e.getValue());
                }
            }
            // The home's bound names are read only for a 2.x bean; WebLogic binds an internal home name for every bean.
            if (ContainerInvocation.call(info, "getHomeInterfaceClass") != null) {
                addName(names, ContainerInvocation.call(info, "getJNDINameAsString"));
            }
            if (ContainerInvocation.call(info, "getLocalHomeInterfaceClass") != null) {
                addName(names, ContainerInvocation.call(info, "getLocalJNDINameAsString"));
            }
        }
        BeanMeta partial = new BeanMeta(app, module, ejbName, beanClass.getName(), interfaces, null, problem);
        for (String iface : interfaces.keySet()) {
            // A 2.x component interface has no java:global binding of its own.
            String global = componentInterfaces.contains(iface) ? null : partial.globalName(iface);
            if (global != null) {
                names.add(global);
            }
        }
        BeanMeta meta = new BeanMeta(app, module, ejbName, beanClass.getName(), interfaces, new ArrayList<String>(names), problem);
        meta.componentInterfaces = componentInterfaces;
        meta.beanType = beanType(info);
        AgentLog.info("metadata " + beanClass.getName() + ": app=" + app + " module=" + module + " ejb=" + ejbName
                + " interfaces=" + interfaces + " jndi=" + meta.jndiNames
                + " via=" + (clientObject == null ? "none" : clientObject.getClass().getName())
                + (problem == null ? "" : " (" + problem + ")"));
        return meta;
    }

    /** SessionBeanInfo.getBusinessLocals() / getBusinessRemotes(): interface name → view. */
    private static Map<String, String> businessInterfaces(Object beanInfo) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String[] view : new String[][]{{"getBusinessLocals", BeanMeta.LOCAL}, {"getBusinessRemotes", BeanMeta.REMOTE}}) {
            Object classes = ContainerInvocation.call(beanInfo, view[0]);
            if (classes instanceof Collection) {
                for (Object c : (Collection<?>) classes) {
                    if (c instanceof Class) {
                        result.put(((Class<?>) c).getName(), view[1]);
                    }
                }
            }
        }
        return result;
    }

    private static void addName(Set<String> names, Object name) {
        if (name != null && !name.toString().isEmpty()) {
            names.add(name.toString());
        }
    }

    /** [application, module] pairs of *EJBRuntime MBeans with this EJB name. */
    private static List<String[]> candidates(MBeanServerConnection server, String ejbName) throws Exception {
        List<String[]> result = new ArrayList<String[]>();
        Set<ObjectName> names = server.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,Name=" + ObjectName.quote(ejbName) + ",*"), null);
        if (names.isEmpty()) {
            names = server.queryNames(new ObjectName("com.bea:Type=*EJBRuntime,Name=" + ejbName + ",*"), null);
        }
        if (names.isEmpty()) {
            // A message-driven bean's runtime MBean is named <ejb>_<destination>; the EJB name is in its EJBName attribute.
            names = new LinkedHashSet<ObjectName>();
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

    private static String beanClassNameOf(Object clientObject) {
        return str(ContainerInvocation.call(beanInfo(clientObject), "getBeanClassName"));
    }

    /** SessionBeanInfo.getBusinessJNDINames(): collects the names, returns interface → view. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> businessJndiNames(Object beanInfo, Set<String> into) {
        Map<String, String> views = new LinkedHashMap<String, String>();
        try {
            Method byInterface = beanInfo.getClass().getMethod("getBusinessJNDINames");
            byInterface.setAccessible(true);
            Method isLocal = beanInfo.getClass().getMethod("isLocalClientView", Class.class);
            isLocal.setAccessible(true);
            for (Map.Entry<Class<?>, Set<String>> e : ((Map<Class<?>, Set<String>>) byInterface.invoke(beanInfo)).entrySet()) {
                views.put(e.getKey().getName(), Boolean.TRUE.equals(isLocal.invoke(beanInfo, e.getKey())) ? BeanMeta.LOCAL : BeanMeta.REMOTE);
                into.addAll(e.getValue());
            }
        } catch (Throwable ignored) {
            // not a session bean: deterministic names only
        }
        return views;
    }

    /** SessionBeanInfo.isStateless/isStateful/isSingleton; a MessageDrivenBeanInfo is message-driven. */
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

    /**
     * The container's BeanInfo behind an object: the object itself, the {@code beanInfo} field of a
     * BaseLocalObject/BaseRemoteObject, or a bounded search through the wrapping the container
     * puts around a JNDI lookup result (proxy, RemoteBusinessIntfProxy, Impl_CBV, Impl).
     */
    static Object beanInfo(Object o) {
        return findBeanInfo(o, 0);
    }

    private static Object findBeanInfo(Object o, int depth) {
        if (o == null || depth > 4) {
            return null;
        }
        if (implementsNamed(o.getClass(), "weblogic.ejb.container.interfaces.BeanInfo")) {
            return o;
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

    /**
     * The MBeanServer that carries the WebLogic runtime tree: the platform server, one of
     * {@code MBeanServerFactory.findMBeanServer}, or the runtime server from JNDI, probed once.
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

    /** WebLogic's MBeanServer wrapper asserts a context class loader, which control threads may lack. */
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

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
