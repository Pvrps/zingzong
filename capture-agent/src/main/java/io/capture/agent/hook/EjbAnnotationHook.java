package io.capture.agent.hook;

import io.capture.agent.AgentConfig;
import io.capture.agent.AgentLog;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Portable hook: instruments classes annotated with the EJB component annotations, matched
 * by annotation <em>name</em> so the agent never links against javax.ejb. Container-generated
 * subclasses are not annotated and therefore not matched; the advice runs in the user's
 * class and reports it as the bean class.
 */
public final class EjbAnnotationHook {

    private static final String[] EJB_ANNOTATIONS = {
            "javax.ejb.Stateless",
            "javax.ejb.Stateful",
            "javax.ejb.Singleton",
            "javax.ejb.MessageDriven",
    };

    private static final String[] IGNORED_PACKAGES = {
            "weblogic.", "oracle.", "com.oracle.", "com.bea.",
            "io.capture.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.eclipse.persistence.", "org.glassfish.", "org.jboss.weld.",
    };

    private EjbAnnotationHook() {
    }

    public static ResettableClassFileTransformer install(Instrumentation inst, final AgentConfig config) {
        ElementMatcher.Junction<TypeDescription> ejbTypes = ElementMatchers.none();
        for (String annotation : EJB_ANNOTATIONS) {
            ejbTypes = ejbTypes.or(isAnnotatedWith(named(annotation)));
        }

        ElementMatcher.Junction<TypeDescription> ignored = isSynthetic();
        for (String prefix : IGNORED_PACKAGES) {
            ignored = ignored.or(nameStartsWith(prefix));
        }

        ElementMatcher<TypeDescription> configured = new ElementMatcher<TypeDescription>() {
            @Override
            public boolean matches(TypeDescription target) {
                return config.accepts(target.getName());
            }
        };

        ElementMatcher.Junction<MethodDescription> businessMethods = isPublic()
                .and(not(isStatic()))
                .and(not(isConstructor()))
                .and(not(isSynthetic()))
                .and(not(isBridge()));

        ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                // No new fields or methods, so already-loaded classes can be retransformed.
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(new HookListener())
                .ignore(ignored)
                .type(ejbTypes.and(configured))
                .transform(new AgentBuilder.Transformer.ForAdvice()
                        // The agent jar is on the system class path (-javaagent) and on the
                        // bootstrap path; locate the advice class file through the former.
                        .include(ClassLoader.getSystemClassLoader())
                        .advice(businessMethods, EjbAdvice.class.getName()))
                .installOn(inst);

        AgentLog.info("annotation hook installed (" + EJB_ANNOTATIONS.length + " annotation names)");
        return transformer;
    }
}
