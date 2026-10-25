package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadata;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * The facts of a call into a bean: the bean class and method, the view it came in through
 * (LOCAL, REMOTE or MESSAGE), the transaction attribute, the destination for a message-driven
 * bean, and the container's metadata for the bean class.
 */
public final class EjbFacts extends SpanFacts {

    private final Class<?> beanClass;
    private final MethodSignature signature;
    private final BeanMetadata metadata;
    private final String interfaceName;
    private final String invocation;
    private final String transactionAttribute;
    private final String destination;

    public EjbFacts(Class<?> beanClass, MethodSignature signature, BeanMetadata metadata, String interfaceName,
                    String invocation, String transactionAttribute, String destination) {
        this.beanClass = beanClass;
        this.signature = signature;
        this.metadata = metadata;
        this.interfaceName = interfaceName;
        this.invocation = invocation;
        this.transactionAttribute = transactionAttribute;
        this.destination = destination;
    }

    @Override
    public MethodSignature signature() {
        return signature;
    }

    @Override
    public void describe(Span span) {
        EjbInfo ejb = new EjbInfo();
        ejb.beanClass = beanClass.getName();
        ejb.method = signature.name;
        ejb.signature = signature.text;
        ejb.interfaceName = interfaceName;
        ejb.invocation = invocation;
        ejb.transactionAttribute = transactionAttribute;
        ejb.destination = destination;
        if (metadata != null) {
            span.app = metadata.application;
            span.module = metadata.module;
            ejb.app = metadata.application;
            ejb.module = metadata.module;
            ejb.beanName = metadata.ejbName;
            ejb.jndiNames = jndiNames();
            ejb.beanType = metadata.beanType;
            ejb.ejb2x = metadata.isComponentInterface(interfaceName) ? Boolean.TRUE : null;
        }
        span.ejb = ejb;
    }

    /** The bean's JNDI names with the invoked interface's portable name first, the rest sorted; null when there are none. */
    private List<String> jndiNames() {
        List<String> names = new ArrayList<>(metadata.jndiNames);
        String invoked = metadata.globalName(interfaceName);
        if (invoked != null && names.remove(invoked)) {
            names.add(0, invoked);
        }
        return names.isEmpty() ? null : names;
    }

    @Override
    public String summary() {
        return beanClass.getSimpleName() + "." + signature.name;
    }
}
