package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadata;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadataResolver;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.MessageDelivery;
import ca.gc.cra.xz02.devops.wltrace.model.EjbInfo;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

import java.util.Optional;

/**
 * Deliveries to message-driven beans through {@code MDListener.onMessage}: an EJB span with
 * invocation {@code MESSAGE}, the destination, and the message as the one argument, recorded as
 * its JMS API view by the serializer. Adds nothing to the base shape.
 */
public final class MessageRecorder extends SpanRecorder<EjbFacts> {

    private static final String MESSAGE_LISTENER_INTERFACE = "javax.jms.MessageListener";
    private static final String ON_MESSAGE_DESCRIPTOR = "(Ljavax/jms/Message;)V";

    private final BeanMetadataResolver metadata;
    private final MethodResolver methods;

    public MessageRecorder(RecorderContext context, BeanMetadataResolver metadata, MethodResolver methods) {
        super(context);
        this.metadata = metadata;
        this.methods = methods;
    }

    @Override
    public String kind() {
        return Span.KIND_EJB;
    }

    /** The {@code MDListener} and the message; the third argument is unused. */
    @Override
    protected EjbFacts readFacts(Object listener, Object message, Object unused) {
        Optional<MessageDelivery> read = MessageDelivery.read(listener);
        if (!read.isPresent()) {
            log.warnOnce("message-delivery", "message listener hook: listener not recognised ("
                    + (listener == null ? "null" : listener.getClass().getName()) + "); delivery not recorded");
            return null;
        }
        MessageDelivery delivery = read.get();
        BeanMetadata beanMetadata = metadata.of(delivery.beanClass, new BeanMetadataResolver.Hint(null, delivery.beanInfo));
        MethodSignature onMessage = methods.resolve(delivery.beanClass, "onMessage", ON_MESSAGE_DESCRIPTOR);
        return new EjbFacts(delivery.beanClass, onMessage, beanMetadata, MESSAGE_LISTENER_INTERFACE,
                EjbInfo.INVOCATION_MESSAGE, delivery.transactionAttribute, delivery.destination);
    }
}
