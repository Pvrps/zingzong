package ca.gc.cra.xz02.devops.wltrace.agent.record;

import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadata;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadataResolver;
import ca.gc.cra.xz02.devops.wltrace.agent.weblogic.SessionInvocation;
import ca.gc.cra.xz02.devops.wltrace.model.Span;

import java.util.Optional;

/**
 * Calls through WebLogic's session invokers: the facts come from the invoker's arguments
 * ({@link SessionInvocation}) and the container's metadata for the bean class. Adds nothing to
 * the base shape: arguments and result are recorded under the interface method's signature.
 */
public final class EjbRecorder extends SpanRecorder<EjbFacts> {

    private final BeanMetadataResolver metadata;
    private final MethodResolver methods;

    public EjbRecorder(RecorderContext context, BeanMetadataResolver metadata, MethodResolver methods) {
        super(context);
        this.metadata = metadata;
        this.methods = methods;
    }

    @Override
    public String kind() {
        return Span.KIND_EJB;
    }

    /** {@code invokable}, the container's client object and the invocation wrapper, as the invoker received them. */
    @Override
    protected EjbFacts readFacts(Object invokable, Object clientObject, Object invocationWrapper) {
        Optional<SessionInvocation> read = SessionInvocation.read(invokable, clientObject, invocationWrapper);
        if (!read.isPresent()) {
            log.warnOnce("session-invocation", "session invoker hook: invocation objects not recognised (" + describe(invokable) + ", "
                    + describe(clientObject) + ", " + describe(invocationWrapper) + "); call not recorded");
            return null;
        }
        SessionInvocation invocation = read.get();
        BeanMetadata beanMetadata = metadata.of(invocation.beanClass, new BeanMetadataResolver.Hint(invocation.ejbName, invocation.beanInfo));
        MethodSignature signature = invocation.method == null
                ? MethodSignature.unknown("?", "")
                : methods.resolve(invocation.beanClass, invocation.method);
        return new EjbFacts(invocation.beanClass, signature, beanMetadata,
                invocation.businessInterface(beanMetadata.interfaces),
                invocation.local ? BeanMetadata.LOCAL : BeanMetadata.REMOTE,
                invocation.transactionAttribute, null);
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
