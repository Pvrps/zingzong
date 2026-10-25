package ca.gc.cra.xz02.devops.wltrace.agent.meta;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the server knows about one bean class; cached per Class by {@link WlsMetadata}. */
public final class BeanMeta {

    public static final String LOCAL = "LOCAL";
    public static final String REMOTE = "REMOTE";

    public final String app;
    public final String module;
    public final String ejbName;
    public final String beanClass;
    /** Business interface name → LOCAL or REMOTE. */
    public final Map<String, String> interfaces;
    /** Every JNDI name the bean is bound under, sorted: java:global/... and descriptor mappings. */
    public final List<String> jndiNames;
    /** STATELESS, STATEFUL, SINGLETON or MESSAGE_DRIVEN as the container says; null when unknown. */
    public String beanType;
    /** EJB 2.x component interfaces (EJBObject / EJBLocalObject views), by name. */
    public Set<String> componentInterfaces = Collections.emptySet();
    /** Why a field is null, for the log; null when complete. */
    public final String problem;

    BeanMeta(String app, String module, String ejbName, String beanClass, Map<String, String> interfaces,
             List<String> jndiNames, String problem) {
        this.app = app;
        this.module = module;
        this.ejbName = ejbName;
        this.beanClass = beanClass;
        this.interfaces = interfaces == null ? Collections.<String, String>emptyMap() : Collections.unmodifiableMap(interfaces);
        this.jndiNames = jndiNames == null ? Collections.<String>emptyList() : Collections.unmodifiableList(jndiNames);
        this.problem = problem;
    }

    /** The portable {@code java:global} name of one interface view, or null when app, module or EJB name is unknown. */
    public String globalName(String interfaceName) {
        if (app == null || module == null || ejbName == null) {
            return null;
        }
        return "java:global/" + app + "/" + module + "/" + ejbName + (interfaceName == null ? "" : "!" + interfaceName);
    }
}
