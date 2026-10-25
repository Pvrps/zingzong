package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One call through a session invoker, read from the invoker's arguments by getter and field
 * name: the generated client object ({@code Invokable}), the container's {@code BaseLocalObject}
 * or {@code BaseRemoteObject} (field {@code beanInfo}) and the {@code InvocationWrapper} (method
 * descriptor, local or remote).
 */
public final class SessionInvocation {

    private static final String REMOTE_OBJECT = "weblogic.ejb.container.internal.BaseRemoteObject";
    /** The generated remote interface of a remote view: {@code <pkg>.<Ejb>_<hash>_<Iface>RIntf}. */
    private static final Pattern GENERATED_REMOTE_INTERFACE = Pattern.compile("(.+)\\.[^.]+_[0-9a-z]+_([^.]+)RIntf$");

    public final String application;
    public final String moduleId;
    public final String ejbName;
    /** The interface method the client called; null when the descriptor has none. */
    public final Method method;
    public final String transactionAttribute;
    public final boolean local;
    public final Class<?> beanClass;
    /** The container's BeanInfo behind the client object. */
    public final Object beanInfo;
    private final Object invokable;

    private SessionInvocation(String application, String moduleId, String ejbName, Method method, String transactionAttribute,
                              boolean local, Class<?> beanClass, Object beanInfo, Object invokable) {
        this.application = application;
        this.moduleId = moduleId;
        this.ejbName = ejbName;
        this.method = method;
        this.transactionAttribute = transactionAttribute;
        this.local = local;
        this.beanClass = beanClass;
        this.beanInfo = beanInfo;
        this.invokable = invokable;
    }

    /** Empty when the objects do not have the shape of the container classes, or name a bean class that cannot be loaded. */
    public static Optional<SessionInvocation> read(Object invokable, Object clientObject, Object invocationWrapper) {
        try {
            Object descriptor = Reflection.call(invocationWrapper, "getMethodDescriptor");
            if (descriptor == null) {
                return Optional.empty();
            }
            Object beanInfo = Reflection.field(clientObject, "beanInfo");
            Class<?> beanClass = beanClassOf(beanInfo, invokable);
            if (beanClass == null) {
                return Optional.empty();
            }
            Object isLocal = Reflection.call(invocationWrapper, "isLocal");
            boolean local = isLocal instanceof Boolean ? (Boolean) isLocal : !Reflection.extendsNamed(clientObject, REMOTE_OBJECT);
            return Optional.of(new SessionInvocation(
                    Reflection.text(Reflection.call(descriptor, "getApplicationName")),
                    Reflection.text(Reflection.call(descriptor, "getModuleId")),
                    Reflection.text(Reflection.call(descriptor, "getEjbName")),
                    (Method) Reflection.call(descriptor, "getMethod"),
                    transactionAttribute(descriptor, beanInfo),
                    local, beanClass, beanInfo, invokable));
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }

    /** The method descriptor's transaction attribute; {@code BEAN} for a bean-managed bean whose descriptor has none. */
    public static String transactionAttribute(Object methodDescriptor, Object beanInfo) {
        Object policy = Reflection.call(methodDescriptor, "getTransactionPolicy");
        String attribute = policy == null ? null : Reflection.text(Reflection.call(policy, "getTxAttribute"));
        if (attribute == null && Boolean.TRUE.equals(Reflection.call(beanInfo, "usesBeanManagedTx"))) {
            return "BEAN";
        }
        return attribute;
    }

    /**
     * The business interface the call came in through, among the candidates (interface name to
     * view): the one the client object implements, else the one named by its generated remote
     * interface, else the only candidate of the call's view; null when undecidable.
     */
    public String businessInterface(Map<String, String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Set<Class<?>> implemented = new LinkedHashSet<>();
        collectInterfaces(invokable.getClass(), implemented);
        for (Class<?> implementedInterface : implemented) {
            if (candidates.containsKey(implementedInterface.getName())) {
                return implementedInterface.getName();
            }
        }
        for (Class<?> implementedInterface : implemented) {
            Matcher generated = GENERATED_REMOTE_INTERFACE.matcher(implementedInterface.getName());
            if (generated.matches()) {
                String resolved = bySimpleName(generated.group(2), candidates);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        String view = local ? BeanMetadata.LOCAL : BeanMetadata.REMOTE;
        String only = null;
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            if (view.equals(candidate.getValue())) {
                if (only != null) {
                    return null;
                }
                only = candidate.getKey();
            }
        }
        return only;
    }

    private static String bySimpleName(String simpleName, Map<String, String> candidates) {
        return candidates.keySet().stream()
                .filter(name -> name.substring(name.lastIndexOf('.') + 1).equals(simpleName))
                .findFirst()
                .orElse(null);
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> into) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Class<?> implemented : current.getInterfaces()) {
                if (into.add(implemented)) {
                    collectInterfaces(implemented, into);
                }
            }
        }
    }

    private static Class<?> beanClassOf(Object beanInfo, Object invokable) {
        String name = beanInfo == null ? null : Reflection.text(Reflection.call(beanInfo, "getBeanClassName"));
        if (name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, invokable.getClass().getClassLoader());
        } catch (Throwable failure) {
            return null;
        }
    }
}
