package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the server knows about one bean class, resolved once per {@link Class} by {@link BeanMetadataResolver}. */
public final class BeanMetadata {

    public static final String LOCAL = "LOCAL";
    public static final String REMOTE = "REMOTE";

    public final String application;
    public final String module;
    public final String ejbName;
    public final String beanClassName;
    /** Business interface name to LOCAL or REMOTE. */
    public final Map<String, String> interfaces;
    /** Every JNDI name the bean is bound under, sorted: {@code java:global/...} and descriptor mappings. */
    public final List<String> jndiNames;
    /** STATELESS, STATEFUL, SINGLETON or MESSAGE_DRIVEN as the container says; null when unknown. */
    public final String beanType;
    /** EJB 2.x component interfaces (EJBObject / EJBLocalObject views), by name. */
    public final Set<String> componentInterfaces;
    /** Why a field is null, for the log; null when complete. */
    public final String problem;

    BeanMetadata(String application, String module, String ejbName, String beanClassName, Map<String, String> interfaces,
                 List<String> jndiNames, String beanType, Set<String> componentInterfaces, String problem) {
        this.application = application;
        this.module = module;
        this.ejbName = ejbName;
        this.beanClassName = beanClassName;
        this.interfaces = Collections.unmodifiableMap(interfaces);
        this.jndiNames = Collections.unmodifiableList(jndiNames);
        this.beanType = beanType;
        this.componentInterfaces = Collections.unmodifiableSet(componentInterfaces);
        this.problem = problem;
    }

    /** The portable {@code java:global} name of one interface view, or null when application, module or EJB name is unknown. */
    public String globalName(String interfaceName) {
        if (application == null || module == null || ejbName == null) {
            return null;
        }
        return "java:global/" + application + "/" + module + "/" + ejbName + (interfaceName == null ? "" : "!" + interfaceName);
    }

    /** True when the interface is an EJB 2.x component interface rather than a business interface. */
    public boolean isComponentInterface(String interfaceName) {
        return interfaceName != null && componentInterfaces.contains(interfaceName);
    }
}
