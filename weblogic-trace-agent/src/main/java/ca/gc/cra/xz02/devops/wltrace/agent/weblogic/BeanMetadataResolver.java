package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;

import javax.management.MBeanServerConnection;
import javax.naming.InitialContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves what the server knows about a bean class: application, module, EJB name, business
 * interfaces with their views, every JNDI name it is bound under, and bean type. The answer is
 * cached per {@link Class} object, so two loaders defining the same bean never share one and a
 * redeployed class gets a fresh answer. Failures degrade to null fields and are logged once per class.
 */
public final class BeanMetadataResolver {

    /** What the container knows at the call site: the descriptor's EJB name and the bean's BeanInfo. */
    public static final class Hint {
        final String ejbName;
        final Object beanInfo;

        public Hint(String ejbName, Object beanInfo) {
            this.ejbName = ejbName;
            this.beanInfo = beanInfo;
        }
    }

    private final RuntimeMBeans runtimeMBeans;
    private final AgentLog log;
    /** The hint of the resolution in progress on this thread; read once, inside {@link #resolve}. */
    private final ThreadLocal<Hint> currentHint = new ThreadLocal<>();
    private final ClassValue<BeanMetadata> cache = new ClassValue<BeanMetadata>() {
        @Override
        protected BeanMetadata computeValue(Class<?> beanClass) {
            return resolve(beanClass, currentHint.get());
        }
    };

    public BeanMetadataResolver(RuntimeMBeans runtimeMBeans, AgentLog log) {
        this.runtimeMBeans = runtimeMBeans;
        this.log = log;
    }

    /** The metadata of a bean class; the hint is used the first time the class is resolved. */
    public BeanMetadata of(Class<?> beanClass, Hint hint) {
        currentHint.set(hint);
        try {
            return cache.get(beanClass);
        } finally {
            currentHint.remove();
        }
    }

    private BeanMetadata resolve(Class<?> beanClass, Hint hint) {
        Object hintInfo = hint == null ? null : hint.beanInfo;
        String ejbName = hint != null && hint.ejbName != null ? hint.ejbName : Reflection.text(Reflection.call(hintInfo, "getEJBName"));
        if (ejbName == null) {
            ejbName = beanClass.getSimpleName();
        }
        Map<String, String> interfaces = BeanInfoReader.businessInterfaces(hintInfo);
        Set<String> componentInterfaces = new HashSet<>();
        addComponentInterface(interfaces, componentInterfaces, hintInfo, "getRemoteInterfaceClass", BeanMetadata.REMOTE);
        addComponentInterface(interfaces, componentInterfaces, hintInfo, "getLocalInterfaceClass", BeanMetadata.LOCAL);

        String application = null;
        String module = null;
        Object clientObject = null;
        String problem = null;
        Set<String> resolvedNames = new TreeSet<>();
        try {
            MBeanServerConnection connection = runtimeMBeans.server();
            if (connection == null) {
                problem = "no MBeanServer with com.bea runtime MBeans reachable";
            } else {
                List<RuntimeMBeans.EjbRuntime> candidates = runtimeMBeans.ejbRuntimes(connection, ejbName);
                if (candidates.isEmpty()) {
                    problem = "no *EJBRuntime MBean named " + ejbName;
                }
                for (RuntimeMBeans.EjbRuntime candidate : candidates) {
                    Object found = lookupClientObject(candidate.application, candidate.module, ejbName, interfaces.keySet(), resolvedNames);
                    if (found != null && beanClass.getName().equals(beanClassNameOf(found))) {
                        application = candidate.application;
                        module = candidate.module;
                        clientObject = found;
                        break;
                    }
                    if (application == null && candidates.size() == 1) {
                        application = candidate.application;
                        module = candidate.module;
                        clientObject = found;
                    }
                }
            }
        } catch (Throwable failure) {
            problem = "metadata lookup failed: " + failure;
        }

        Set<String> names = new TreeSet<>(resolvedNames);
        Object beanInfo = BeanInfoReader.findBeanInfo(clientObject != null ? clientObject : hintInfo);
        if (beanInfo != null) {
            BeanInfoReader.businessJndiNames(beanInfo, names).forEach(interfaces::putIfAbsent);
            // The home's bound names are read only for a 2.x bean: WebLogic binds an internal home name for every bean.
            if (Reflection.call(beanInfo, "getHomeInterfaceClass") != null) {
                addName(names, Reflection.call(beanInfo, "getJNDINameAsString"));
            }
            if (Reflection.call(beanInfo, "getLocalHomeInterfaceClass") != null) {
                addName(names, Reflection.call(beanInfo, "getLocalJNDINameAsString"));
            }
        }
        BeanMetadata partial = new BeanMetadata(application, module, ejbName, beanClass.getName(), interfaces, new ArrayList<>(),
                null, componentInterfaces, problem);
        for (String interfaceName : interfaces.keySet()) {
            // A 2.x component interface has no java:global binding of its own.
            String global = componentInterfaces.contains(interfaceName) ? null : partial.globalName(interfaceName);
            if (global != null) {
                names.add(global);
            }
        }
        BeanMetadata metadata = new BeanMetadata(application, module, ejbName, beanClass.getName(), interfaces,
                new ArrayList<>(names), BeanInfoReader.beanType(beanInfo), componentInterfaces, problem);
        log.info("metadata " + beanClass.getName() + ": app=" + application + " module=" + module + " ejb=" + ejbName
                + " interfaces=" + interfaces + " jndi=" + metadata.jndiNames
                + " via=" + (clientObject == null ? "none" : clientObject.getClass().getName())
                + (problem == null ? "" : " (" + problem + ")"));
        return metadata;
    }

    private static void addComponentInterface(Map<String, String> interfaces, Set<String> componentInterfaces, Object beanInfo,
                                              String getter, String view) {
        Class<?> component = BeanInfoReader.componentInterface(beanInfo, getter);
        if (component != null && !interfaces.containsKey(component.getName())) {
            interfaces.put(component.getName(), view);
            componentInterfaces.add(component.getName());
        }
    }

    /** The client object under one of the portable names; the name that resolved is added to {@code resolvedNames}. */
    private static Object lookupClientObject(String application, String module, String ejbName, Set<String> interfaces, Set<String> resolvedNames) {
        List<String> candidates = new ArrayList<>();
        candidates.add("java:global/" + application + "/" + module + "/" + ejbName);
        for (String interfaceName : interfaces) {
            candidates.add("java:global/" + application + "/" + module + "/" + ejbName + "!" + interfaceName);
        }
        for (String name : candidates) {
            try {
                // A lookup inside the server needs no credentials.
                Object found = new InitialContext().lookup(name);
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
        return Reflection.text(Reflection.call(BeanInfoReader.findBeanInfo(clientObject), "getBeanClassName"));
    }

    private static void addName(Set<String> names, Object name) {
        if (name != null && !name.toString().isEmpty()) {
            names.add(name.toString());
        }
    }
}
