package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog;
import ca.gc.cra.xz02.devops.wltrace.agent.record.ResourceRecorder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * The public instance methods of JAX-RS resource classes ({@code javax.ws.rs.Path}) and JAX-WS
 * endpoints ({@code javax.jws.WebService}) that an application loader defines. A class that is
 * also an EJB is left to {@link SessionInvokerHook}; a resource class another loader defines (the
 * server's own) is kept out and logged once.
 */
public final class ResourceHook extends Hook {

    private static final List<String> EJB_ANNOTATIONS = Arrays.asList(
            "javax.ejb.Stateless", "javax.ejb.Stateful", "javax.ejb.Singleton", "javax.ejb.MessageDriven");
    private static final List<String> RESOURCE_ANNOTATIONS = Arrays.asList("javax.ws.rs.Path", "javax.jws.WebService");

    private final AgentLog log;

    public ResourceHook(ResourceRecorder recorder, AgentLog log) {
        super("resource classes", recorder);
        this.log = log;
    }

    @Override
    public List<String> targetClassNames() {
        return Collections.emptyList();
    }

    @Override
    public List<AdviceBinding> bindings() {
        ElementMatcher.Junction<TypeDescription> resourceTypes = annotatedWithAny(RESOURCE_ANNOTATIONS).and(not(annotatedWithAny(EJB_ANNOTATIONS)));
        ElementMatcher.Junction<MethodDescription> businessMethods = isPublic()
                .and(not(isStatic()))
                .and(not(isConstructor()))
                .and(not(isSynthetic()))
                .and(not(isBridge()));
        return Collections.singletonList(new AdviceBinding(ownedByApplication(resourceTypes), businessMethods, ResourceAdvice.class));
    }

    private TypeMatcher ownedByApplication(ElementMatcher<TypeDescription> annotated) {
        return (type, loader) -> {
            if (!annotated.matches(type)) {
                return false;
            }
            if (ApplicationClassLoaders.isApplication(loader)) {
                return true;
            }
            log.warnOnce("kept-out:" + type.getName(), "not instrumented (resource annotation, but not an application class): "
                    + type.getName() + " loader=" + InstrumentationListener.describe(loader));
            return false;
        };
    }

    private static ElementMatcher.Junction<TypeDescription> annotatedWithAny(List<String> annotationNames) {
        ElementMatcher.Junction<TypeDescription> matcher = ElementMatchers.none();
        for (String annotationName : annotationNames) {
            matcher = matcher.or(isAnnotatedWith(named(annotationName)));
        }
        return matcher;
    }
}
