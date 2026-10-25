package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
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
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * The agent's instrumentation, installed while a recording is active and removed when it
 * stops. Advises WebLogic's session-bean invokers ({@link ContainerAdvice}), the MDB listener
 * ({@link MessageAdvice}), the servlet pipeline ({@link ServletAdvice}), the JDBC wrappers
 * ({@link JdbcAdvice}) and, in application loaders only, JAX-RS and JAX-WS resource classes
 * ({@link ResourceAdvice}).
 */
public final class CaptureHook {

    private static final String[] EJB_ANNOTATIONS = {
            "javax.ejb.Stateless",
            "javax.ejb.Stateful",
            "javax.ejb.Singleton",
            "javax.ejb.MessageDriven",
    };

    private static final String[] RESOURCE_ANNOTATIONS = {
            "javax.ws.rs.Path",
            "javax.jws.WebService",
    };

    /**
     * Never instrumented wherever they are loaded: the JVM, the agent, the server's own packages
     * and the frameworks it embeds, which an application may also bundle.
     */
    private static final String[] IGNORED_PACKAGES = {
            "weblogic.", "oracle.", "com.oracle.", "com.bea.",
            "ca.gc.cra.xz02.devops.wltrace.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.eclipse.persistence.", "org.glassfish.", "org.jboss.weld.",
    };

    private final Instrumentation instrumentation;
    private final HookListener listener = new HookListener();
    private final boolean jdbc = JdbcTargets.present();
    private ResettableClassFileTransformer transformer;

    public CaptureHook(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public synchronized boolean isInstalled() {
        return transformer != null;
    }

    public synchronized void install() {
        if (transformer != null) {
            return;
        }
        long start = System.nanoTime();
        listener.resetCount();
        transformer = builder().installOn(instrumentation);
        retransformMissedTargets();
        AgentLog.info("hook installed: " + listener.count() + " classes instrumented in "
                + (System.nanoTime() - start) / 1000000L + "ms");
    }

    public synchronized void uninstall() {
        if (transformer == null) {
            return;
        }
        long start = System.nanoTime();
        boolean reset = transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        transformer = null;
        AgentLog.info("hook removed (" + (reset ? "bytecode restored" : "nothing to restore") + ") in "
                + (System.nanoTime() - start) / 1000000L + "ms");
    }

    /**
     * Retransforms named targets that the install pass loaded as a side effect: describing a
     * loaded type resolves the types its methods mention, and Byte Buddy's circularity lock
     * skips a class loaded that way.
     */
    private void retransformMissedTargets() {
        List<Class<?>> missed = loadedTargetsNotTransformed();
        if (!missed.isEmpty()) {
            try {
                instrumentation.retransformClasses(missed.toArray(new Class<?>[0]));
                AgentLog.info("retransformed " + missed.size() + " hook target(s) loaded during installation: " + missed);
            } catch (Throwable t) {
                AgentLog.error("retransformation of hook targets loaded during installation failed: " + missed, t);
            }
        }
        List<Class<?>> still = loadedTargetsNotTransformed();
        if (!still.isEmpty()) {
            AgentLog.warn("hook targets loaded but not instrumented (no method of the expected shape): " + still);
        }
    }

    private List<Class<?>> loadedTargetsNotTransformed() {
        List<Class<?>> result = new ArrayList<Class<?>>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName();
            if (ContainerTargets.isNamed(name) && !listener.transformed(name) && (jdbc || !JdbcTargets.isNamed(name))) {
                result.add(loaded);
            }
        }
        return result;
    }

    /** Matches annotated types defined by an application loader; logs, once per class, those another loader defined. */
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
        // An EJB that is also a resource is seen by the container hook; it stays an EJB span.
        resourceTypes = resourceTypes.and(not(ejbTypes));

        ElementMatcher.Junction<TypeDescription> ignored = isSynthetic();
        for (String prefix : IGNORED_PACKAGES) {
            ignored = ignored.or(nameStartsWith(prefix));
        }
        for (String target : ContainerTargets.ALL) {
            ignored = ignored.and(not(named(target)));
        }
        for (String wrapper : JdbcTargets.ALL) {
            ignored = ignored.and(not(named(wrapper)));
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

        for (String invoker : ContainerTargets.INVOKERS) {
            builder = builder.type(named(invoker))
                    .transform(advice(named("invoke").and(isStatic()).and(takesArguments(5)), ContainerAdvice.class));
        }
        builder = builder.type(named(ContainerTargets.MESSAGE_LISTENER))
                .transform(advice(named("onMessage").and(not(isStatic())).and(takesArguments(1)), MessageAdvice.class));
        if (jdbc) {
            builder = builder.type(named(JdbcTargets.STATEMENT).or(named(JdbcTargets.PREPARED_STATEMENT)).or(named(JdbcTargets.CALLABLE_STATEMENT)))
                    .transform(forAdvice()
                            .advice(isPublic().and(not(isStatic())).and(nameStartsWith("execute")), JdbcAdvice.Execute.class.getName())
                            .advice(isPublic().and(not(isStatic())).and(nameStartsWith("set").and(takesArgument(0, int.class)).or(named("addBatch")).or(named("clearParameters"))), JdbcAdvice.Bind.class.getName()))
                    .type(named(JdbcTargets.RESULT_SET))
                    .transform(advice(named("postInvocationHandler").and(takesArguments(3)), JdbcAdvice.Next.class));
        }
        return builder
                .type(ownedBy(resourceTypes, "resource"))
                .transform(advice(businessMethods, ResourceAdvice.class))
                .type(named(ContainerTargets.FILTER_CHAIN))
                .transform(advice(named("doFilter").and(takesArguments(2)), ServletAdvice.class))
                .type(named(ContainerTargets.SERVLET_STUB))
                .transform(advice(named("execute").and(takesArguments(2)), ServletAdvice.class));
    }

    private static AgentBuilder.Transformer advice(ElementMatcher<? super MethodDescription> methods, Class<?> adviceClass) {
        return forAdvice().advice(methods, adviceClass.getName());
    }

    /** The agent jar is on the system class path and on the bootstrap path; the advice class file is located through the former. */
    private static AgentBuilder.Transformer.ForAdvice forAdvice() {
        return new AgentBuilder.Transformer.ForAdvice().include(ClassLoader.getSystemClassLoader());
    }
}
