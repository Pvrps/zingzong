package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;

import ca.gc.cra.xz02.devops.wltrace.agent.instrument.AdviceBinding;
import ca.gc.cra.xz02.devops.wltrace.agent.instrument.Hook;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Proves that the agent stays inert outside WebLogic and when any hook's targets are missing, and records when they are all present. */
public class InertnessCheckTest {

    private static final Hook PRESENT = hook("present", "java.lang.String");
    private static final Hook MISSING = hook("missing", "weblogic.nowhere.Absent");

    @Test
    public void inertOutsideWebLogic() {
        Optional<String> reason = InertnessCheck.reason(Collections.singletonList(PRESENT));
        assertTrue(reason.isPresent());
        assertTrue(reason.get().startsWith("not a WebLogic server"), reason.get());
    }

    @Test
    public void activeOnWebLogicWithEveryTargetPresent() {
        withWebLogicHome(() -> assertFalse(InertnessCheck.reason(Collections.singletonList(PRESENT)).isPresent()));
    }

    @Test
    public void inertOnWebLogicWhenAnyTargetIsMissing() {
        withWebLogicHome(() -> {
            Optional<String> reason = InertnessCheck.reason(Arrays.asList(PRESENT, MISSING));
            assertTrue(reason.isPresent());
            assertEquals(reason.get(), "WebLogic without the expected container classes [weblogic.nowhere.Absent]");
        });
    }

    private static void withWebLogicHome(Runnable check) {
        System.setProperty("weblogic.home", "/opt/wls");
        try {
            check.run();
        } finally {
            System.clearProperty("weblogic.home");
        }
    }

    private static Hook hook(String name, String... targets) {
        return new Hook(name, null) {
            @Override
            public List<String> targetClassNames() {
                return Arrays.asList(targets);
            }

            @Override
            public List<AdviceBinding> bindings() {
                return Collections.emptyList();
            }
        };
    }
}
