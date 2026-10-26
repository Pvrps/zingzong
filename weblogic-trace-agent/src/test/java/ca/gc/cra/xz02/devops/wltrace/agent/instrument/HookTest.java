package ca.gc.cra.xz02.devops.wltrace.agent.instrument;

import net.bytebuddy.description.type.TypeDescription;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves what the hook base knows from its named targets alone, through a stub subclass: which classes it targets, which are missing, and its name. */
public class HookTest {

    private final Hook stub = new Hook("stub", null) {
        @Override
        public List<String> targetClassNames() {
            return Arrays.asList("java.lang.String", "weblogic.nowhere.Absent");
        }

        @Override
        public List<AdviceBinding> bindings() {
            return Collections.emptyList();
        }
    };

    @Test
    public void targetsItsNamedClassesOnly() {
        assertTrue(stub.targets("java.lang.String"));
        assertFalse(stub.targets("java.lang.Integer"));
    }

    @Test
    public void reportsTheNamedClassesTheSystemClassPathCannotResolve() {
        assertEquals(stub.missingTargetClasses(), Collections.singletonList("weblogic.nowhere.Absent"));
    }

    @Test
    public void isKnownByItsName() {
        assertEquals(stub.name(), "stub");
        assertEquals(stub.toString(), "stub");
    }

    @Test
    public void namedTypeMatcherMatchesExactNamesInAnyLoader() {
        TypeMatcher matcher = TypeMatcher.named("java.lang.String", "java.lang.Integer");
        assertTrue(matcher.matches(TypeDescription.ForLoadedType.of(String.class), null));
        assertTrue(matcher.matches(TypeDescription.ForLoadedType.of(Integer.class), ClassLoader.getSystemClassLoader()));
        assertFalse(matcher.matches(TypeDescription.ForLoadedType.of(Long.class), null));
    }

    @Test
    public void theFiveHooksNameTheWebLogicClassesTheyAdvise() {
        assertEquals(new SessionInvokerHook(null).targetClassNames(),
                Arrays.asList("weblogic.ejb.container.internal.SessionLocalMethodInvoker", "weblogic.ejb.container.internal.SessionRemoteMethodInvoker"));
        assertEquals(new MessageListenerHook(null).targetClassNames(), Collections.singletonList("weblogic.ejb.container.internal.MDListener"));
        assertEquals(new ServletPipelineHook(null).targetClassNames(),
                Arrays.asList("weblogic.servlet.internal.FilterChainImpl", "weblogic.servlet.internal.ServletStubImpl"));
        assertEquals(new JdbcStatementHook(null).targetClassNames().size(), 4);
        assertTrue(new ResourceHook(null, null).targetClassNames().isEmpty(), "resource classes are matched by annotation and loader, not by name");
    }
}
