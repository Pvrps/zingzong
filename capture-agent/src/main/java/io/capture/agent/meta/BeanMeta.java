package io.capture.agent.meta;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** What the server knows about one bean class. Immutable; cached per Class by {@link WlsMetadata}. */
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

    public String invocationOf(String interfaceName) {
        return interfaceName == null ? null : interfaces.get(interfaceName);
    }

    /** The deterministic portable name for one interface. */
    public String globalName(String interfaceName) {
        if (app == null || module == null || ejbName == null) {
            return null;
        }
        return "java:global/" + app + "/" + module + "/" + ejbName + (interfaceName == null ? "" : "!" + interfaceName);
    }
}
