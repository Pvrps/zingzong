package io.capture.agent.hook;

import io.capture.agent.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * The agent's instrumentation, installed only while a recording is active. EJB calls are
 * captured by one of two strategies:
 * <ul>
 *   <li>{@link EjbStrategy#CONTAINER}: advice on WebLogic's {@code SessionLocalMethodInvoker} /
 *       {@code SessionRemoteMethodInvoker} ({@link ContainerAdvice}), one choke point for every
 *       business-interface call, with the container's own view of interface, transaction
 *       attribute and bean. Preferred on WebLogic.</li>
 *   <li>{@link EjbStrategy#ANNOTATION}: advice inlined into classes annotated with the EJB
 *       component annotations, matched by name ({@link EjbAdvice}). Portable; the fallback.</li>
 * </ul>
 * JAX-RS/JAX-WS resources ({@link ResourceAdvice}) and the servlet pipeline ({@link ServletAdvice})
 * are instrumented the same way under both.
 */
public final class CaptureHook implements InstrumentationHook {

    public enum EjbStrategy { ANNOTATION, CONTAINER }

    private static final String[] EJB_ANNOTATIONS = {
            "javax.ejb.Stateless",
            "javax.ejb.Stateful",
            "javax.ejb.Singleton",
            "javax.ejb.MessageDriven",
    };

    /** Resource-layer classes become HTTP child spans; an EJB that is also a resource stays an EJB span. */
    private static final String[] RESOURCE_ANNOTATIONS = {
            "javax.ws.rs.Path",
            "javax.jws.WebService",
    };

    /** The container's choke points for this WebLogic ({@link ContainerTargets}); null with the annotation strategy. */
    private final ContainerTargets.Target target;

    /** WebLogic's servlet pipeline entries taking the servlet API interfaces (docs/container-hook.md). */
    static final String FILTER_CHAIN = "weblogic.servlet.internal.FilterChainImpl";
    static final String SERVLET_STUB = "weblogic.servlet.internal.ServletStubImpl";

    /**
     * Never instrumented wherever they are loaded: the JVM, the agent, the server's own packages
     * and the frameworks it embeds (which an application may also bundle). Vendor code outside
     * these packages is kept out by {@link ApplicationLoaders}: application classes are
     * recognised by their defining loader, not by a growing list of package prefixes.
     */
    private static final String[] IGNORED_PACKAGES = {
            "weblogic.", "oracle.", "com.oracle.", "com.bea.",
            "io.capture.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.eclipse.persistence.", "org.glassfish.", "org.jboss.weld.",
    };

    private final Instrumentation instrumentation;
    private final EjbStrategy strategy;
    private final HookListener listener = new HookListener();
    private ResettableClassFileTransformer transformer;

    public CaptureHook(Instrumentation instrumentation, EjbStrategy strategy) {
        this(instrumentation, strategy, strategy == EjbStrategy.CONTAINER ? ContainerTargets.resolve() : null);
    }

    public CaptureHook(Instrumentation instrumentation, EjbStrategy strategy, ContainerTargets.Target target) {
        if (strategy == EjbStrategy.CONTAINER && target == null) {
            throw new IllegalArgumentException("container strategy without a resolved target");
        }
        this.instrumentation = instrumentation;
        this.strategy = strategy;
        this.target = target;
    }

    public EjbStrategy strategy() {
        return strategy;
    }

    @Override
    public String name() {
        return strategy.name().toLowerCase();
    }

    /** The server classes advised by name (container invokers of any known shape, servlet pipeline). */
    static boolean isNamedTarget(String typeName) {
        return typeName.equals(FILTER_CHAIN) || typeName.equals(SERVLET_STUB) || ContainerTargets.isNamedInvoker(typeName);
    }

    /** The container classes this hook advises for EJBs: the session invokers and the MDB listener. */
    private String[] invokers() {
        if (target == null) {
            return new String[0];
        }
        String[] all = new String[target.invokers.length + 1];
        System.arraycopy(target.invokers, 0, all, 0, target.invokers.length);
        all[target.invokers.length] = target.messageListener;
        return all;
    }

    @Override
    public synchronized boolean isInstalled() {
        return transformer != null;
    }

    @Override
    public synchronized void install() {
        if (transformer != null) {
            return;
        }
        long start = System.nanoTime();
        listener.resetCount();
        transformer = builder().installOn(instrumentation);
        retransformMissedTargets();
        AgentLog.info(strategy.name().toLowerCase() + " hook installed: " + listener.count() + " classes instrumented in "
                + (System.nanoTime() - start) / 1000000L + "ms");
    }

    /**
     * A named target that the install pass loaded as a side effect (describing a loaded type
     * reflectively resolves the types its methods mention: FilterChainImpl while
     * ServletStubImpl is retransformed) is skipped by Byte Buddy's circularity lock and was
     * not in the loaded-class list either. Retransform such classes explicitly now.
     */
    private void retransformMissedTargets() {
        List<Class<?>> missed = loadedTargetsNotTransformed();
        if (missed.isEmpty()) {
            return;
        }
        try {
            instrumentation.retransformClasses(missed.toArray(new Class<?>[0]));
            AgentLog.info("retransformed " + missed.size() + " hook target(s) loaded during installation: " + missed);
        } catch (Throwable t) {
            AgentLog.error("retransformation of hook targets loaded during installation failed: " + missed, t);
        }
    }

    private List<Class<?>> loadedTargetsNotTransformed() {
        List<Class<?>> result = new ArrayList<Class<?>>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName();
            if (isNamedTarget(name) && !listener.transformed(name) && appliesTo(name)) {
                result.add(loaded);
            }
        }
        return result;
    }

    private boolean appliesTo(String targetName) {
        if (targetName.equals(FILTER_CHAIN) || targetName.equals(SERVLET_STUB)) {
            return true;
        }
        return strategy == EjbStrategy.CONTAINER;
    }

    @Override
    public synchronized void uninstall() {
        if (transformer == null) {
            return;
        }
        long start = System.nanoTime();
        boolean reset = transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        transformer = null;
        AgentLog.info(strategy.name().toLowerCase() + " hook removed (" + (reset ? "bytecode restored" : "nothing to restore") + ") in "
                + (System.nanoTime() - start) / 1000000L + "ms");
    }

    /**
     * Self-check for the container strategy: true when the invoker classes are loaded but were
     * not transformed, i.e. the advice found no {@code invoke} method with the expected shape.
     */
    public synchronized boolean containerTargetsMissed() {
        if (strategy != EjbStrategy.CONTAINER || transformer == null) {
            return false;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            for (String name : invokers()) {
                if (loaded.getName().equals(name) && !listener.transformed(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * An annotated type is instrumented only when an application loader defined it; a vendor
     * class that carries the annotation on the server class path is logged as kept out, once.
     */
    private AgentBuilder.RawMatcher ownedBy(final ElementMatcher<TypeDescription> annotated, final String layer) {
        return new AgentBuilder.RawMatcher() {
            @Override
            public boolean matches(TypeDescription type, ClassLoader loader, net.bytebuddy.utility.JavaModule module,
                                   Class<?> classBeingRedefined, java.security.ProtectionDomain protectionDomain) {
                if (!annotated.matches(type)) {
                    return false;
                }
                if (ApplicationLoaders.isApplication(loader)) {
                    return true;
                }
                listener.keptOut(layer, type.getName(), loader);
                return false;
            }
        };
    }

    private AgentBuilder builder() {
        ElementMatcher.Junction<TypeDescription> ejbTypes = ElementMatchers.none();
        for (String annotation : EJB_ANNOTATIONS) {
            ejbTypes = ejbTypes.or(isAnnotatedWith(named(annotation)));
        }
        ElementMatcher.Junction<TypeDescription> resourceTypes = ElementMatchers.none();
        for (String annotation : RESOURCE_ANNOTATIONS) {
            resourceTypes = resourceTypes.or(isAnnotatedWith(named(annotation)));
        }
        resourceTypes = resourceTypes.and(not(ejbTypes));

        ElementMatcher.Junction<TypeDescription> ignored = isSynthetic();
        for (String prefix : IGNORED_PACKAGES) {
            ignored = ignored.or(nameStartsWith(prefix));
        }
        ignored = ignored.and(not(named(FILTER_CHAIN))).and(not(named(SERVLET_STUB)));
        for (String invoker : invokers()) {
            ignored = ignored.and(not(named(invoker)));
        }

        ElementMatcher.Junction<MethodDescription> businessMethods = isPublic()
                .and(not(isStatic()))
                .and(not(isConstructor()))
                .and(not(isSynthetic()))
                .and(not(isBridge()));

        AgentBuilder builder = new AgentBuilder.Default()
                // No new fields or methods, so loaded classes can be retransformed and restored.
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(listener)
                .ignore(ignored);

        if (strategy == EjbStrategy.ANNOTATION) {
            builder = builder.type(ownedBy(ejbTypes, "EJB"))
                    .transform(new AgentBuilder.Transformer.ForAdvice()
                            // The agent jar is on the system class path (-javaagent) and on the
                            // bootstrap path; locate the advice class file through the former.
                            .include(ClassLoader.getSystemClassLoader())
                            .advice(businessMethods, EjbAdvice.class.getName()));
        } else {
            for (String invoker : target.invokers) {
                builder = builder.type(named(invoker))
                        .transform(new AgentBuilder.Transformer.ForAdvice()
                                .include(ClassLoader.getSystemClassLoader())
                                .advice(named("invoke").and(isStatic()).and(takesArguments(5)), ContainerAdvice.class.getName()));
            }
            builder = builder.type(named(target.messageListener))
                    .transform(new AgentBuilder.Transformer.ForAdvice()
                            .include(ClassLoader.getSystemClassLoader())
                            .advice(named("onMessage").and(not(isStatic())).and(takesArguments(1)), MessageAdvice.class.getName()));
        }
        return builder
                .type(ownedBy(resourceTypes, "resource"))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .include(ClassLoader.getSystemClassLoader())
                        .advice(businessMethods, ResourceAdvice.class.getName()))
                .type(named(FILTER_CHAIN))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .include(ClassLoader.getSystemClassLoader())
                        .advice(named("doFilter").and(takesArguments(2)), ServletAdvice.class.getName()))
                .type(named(SERVLET_STUB))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        .include(ClassLoader.getSystemClassLoader())
                        .advice(named("execute").and(takesArguments(2)), ServletAdvice.class.getName()));
    }
}
