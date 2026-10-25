package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import java.util.Optional;

/**
 * One message delivered to a message-driven bean, read from the {@code MDListener}'s private
 * {@code info} field, a {@code MessageDrivenBeanInfo}: the bean class in its own loader, the
 * destination and the transaction attribute of {@code onMessage}.
 */
public final class MessageDelivery {

    public final Class<?> beanClass;
    public final String destination;
    public final String transactionAttribute;
    /** The container's MessageDrivenBeanInfo. */
    public final Object beanInfo;

    private MessageDelivery(Class<?> beanClass, String destination, String transactionAttribute, Object beanInfo) {
        this.beanClass = beanClass;
        this.destination = destination;
        this.transactionAttribute = transactionAttribute;
        this.beanInfo = beanInfo;
    }

    /** Empty when the listener has no {@code info} naming a loadable bean class. */
    public static Optional<MessageDelivery> read(Object listener) {
        try {
            Object info = Reflection.field(listener, "info");
            String className = Reflection.text(Reflection.call(info, "getBeanClassName"));
            Object loader = Reflection.call(info, "getClassLoader");
            if (className == null || !(loader instanceof ClassLoader)) {
                return Optional.empty();
            }
            Class<?> beanClass = Class.forName(className, false, (ClassLoader) loader);
            String destination = Reflection.text(Reflection.call(info, "getDestinationName"));
            String transactionAttribute = SessionInvocation.transactionAttribute(Reflection.call(info, "getOnMessageMethodDescriptor"), info);
            return Optional.of(new MessageDelivery(beanClass, destination, transactionAttribute, info));
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }
}
